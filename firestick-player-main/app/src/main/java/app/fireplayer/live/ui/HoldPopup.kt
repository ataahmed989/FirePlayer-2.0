package app.fireplayer.live.ui

import android.content.Context
import android.graphics.RectF
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.Rows

/**
 * The small window a held OK opens in Live TV: a channel's in the guide (Favorite, Assign favorites) and a group's in
 * the groups column (Move to top, Hide category). One look and one behaviour for both: the full name (up to two lines)
 * over a row of buttons, beside what was held, with the first button selected, and optionally one more button under
 * that row (a group's Manage groups), reached with Down and left with Up. Left from the first button, Right from the
 * last, Up from the row, Down from the bottom and Back close it (LivePage passes Back on), and the selection goes back
 * where it was.
 *
 * Light, for a slow TV: its few views are made once and reused; it's laid out once as it opens (its words and place),
 * then only faded and grown in or out, drawn as one layer while that lasts. It sits in its parent, a FrameLayout over
 * what it was opened from, and is GONE while closed.
 */
class HoldPopup(context: Context, private val t: Tokens) : LinearLayout(context) {

    /** A button: its words, or an [icon] with [label] as what it says to a screen reader. [selected] shows it blue. */
    class Button(val label: String, val icon: Int? = null, val selected: Boolean = false, val onClick: () -> Unit)

    private class Slot(val view: LinearLayout, val icon: ImageView, val text: TextView) {
        /** Round (an icon) or a pill (words); null until first shown. */
        var round: Boolean? = null
        var action: (() -> Unit)? = null
    }

    private val rows = Rows(context)
    private val title = t.label(context, 15f, t.semibold).apply {
        // The whole name: a second line rather than cut short.
        maxLines = 2
        maxWidth = t.dp(300)
        setLineSpacing(t.dpf(1f), 1f)
    }
    private val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val slots = ArrayList<Slot>()
    private var shown = 0
    /** The button under the row, as wide as the window; made the first time one is asked for. */
    private var below: Slot? = null
    private var belowShown = false
    /** The row's button Down left from, for Up to come back to. */
    private var lastInRow = 0

    var isOpen = false
        private set

    /** Runs as it closes, before it fades: [focusBack] unless something else took the selection meanwhile. */
    var onClose: ((focusBack: Boolean) -> Unit)? = null

    /** False until OK is pressed anew here: the OK that opened it may still be held, and its release does nothing. */
    private var armed = false
    /** Bumped on every open and close: a fade waiting for its first frame starts only if still current. */
    private var serial = 0

    // Anything else taking the selection (the settings window, full screen) closes it, leaving the selection there.
    private val watch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (isOpen && (now == null || !isInside(now))) close(focusBack = false)
    }

    private val closeLater = Runnable { close() }

    init {
        orientation = VERTICAL
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(12))
        minimumWidth = t.dp(200)
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(10) })
        applyLook()
    }

    /** The look's surfaces: a popup's glass (or the classic popup), its buttons the pills of the app's small windows. */
    fun applyLook() {
        Glass.background(this, Glass.Kind.POPUP)
        for (s in slots) if (s.round != null) style(s)
        below?.let { if (it.round != null) style(it) }
    }

    /**
     * Opens with [name] over [buttons], beside [anchor] (the held row, in the parent's coordinates) and inside [area];
     * the first button is selected.
     */
    fun open(name: CharSequence, buttons: List<Button>, anchor: RectF, area: RectF, under: Button? = null) {
        val host = parent as? View ?: return
        if (buttons.isEmpty()) return
        serial++
        removeCallbacks(closeLater)
        animate().cancel()
        title.text = name
        shown = buttons.size
        buttons.forEachIndexed { i, b -> fill(slot(i), b) }
        for (i in buttons.size until slots.size) slots[i].view.visibility = GONE
        belowShown = under != null
        if (under != null) fill(belowSlot(), under) else below?.view?.visibility = GONE
        lastInRow = 0
        isOpen = true
        armed = false
        visibility = VISIBLE
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // Measured now so it's placed before it's first drawn: one layout, with its new words.
        measure(
            MeasureSpec.makeMeasureSpec(host.width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(host.height, MeasureSpec.AT_MOST),
        )
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()
        val x = HoldPlace.x(anchor.right, w, t.dpf(8f), area.left, area.right)
        val y = HoldPlace.y(anchor.centerY(), h, area.top, area.bottom)
        // Placed by its layout, not a transform: the glass look lines its blurred wallpaper up with where it's laid out.
        (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.leftMargin = x.toInt()
            lp.topMargin = y.toInt()
            layoutParams = lp
        }
        // It grows out of the held row's side.
        pivotX = 0f
        pivotY = (anchor.centerY() - y).coerceIn(0f, h)
        alpha = 0f
        scaleX = OPEN_SCALE
        scaleY = OPEN_SCALE
        slots[0].view.requestFocus()
        viewTreeObserver.addOnGlobalFocusChangeListener(watch)
        // Fades in from the next frame (the first one lays it out and draws it), as one layer while it fades.
        val s = serial
        Choreographer.getInstance().postFrameCallback {
            if (s != serial || !isOpen) return@postFrameCallback
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(OPEN_MS).setInterpolator(EASE).withLayer().start()
        }
    }

    /**
     * Closes it: the selection goes back first ([focusBack], see [onClose]), so the buttons fading out never hold it.
     * [fade] false: gone at once (a window opening in its place).
     */
    fun close(fade: Boolean = true, focusBack: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        serial++
        removeCallbacks(closeLater)
        viewTreeObserver.removeOnGlobalFocusChangeListener(watch)
        onClose?.invoke(focusBack)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        animate().cancel()
        if (!fade || !isShown || alpha <= 0f) {
            visibility = GONE
            return
        }
        animate().alpha(0f).scaleX(CLOSE_SCALE).scaleY(CLOSE_SCALE).setDuration(CLOSE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!isOpen) visibility = GONE }
            .start()
    }

    /** Closes by itself after [ms], so the viewer sees what a press did; moving to another button keeps it open. */
    fun closeAfter(ms: Long) {
        removeCallbacks(closeLater)
        postDelayed(closeLater, ms)
    }

    /** Button [i] shows [b] now (the star after a press), in place. */
    fun update(i: Int, b: Button) {
        if (i < shown) fill(slots[i], b)
    }

    /** A small pop on button [i]'s icon: it springs back from smaller (a transform, nothing laid out). */
    fun pop(i: Int) {
        val icon = slots.getOrNull(i)?.icon ?: return
        icon.animate().cancel()
        icon.scaleX = POP_SCALE
        icon.scaleY = POP_SCALE
        icon.animate().scaleX(1f).scaleY(1f).setDuration(POP_MS).setInterpolator(OVERSHOOT).start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) armed = true
                return if (armed) super.dispatchKeyEvent(event) else true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) vertical(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN, event.repeatCount > 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) step(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount > 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Left / Right: the next button, or past the first or last one, closed (a held key stops at the ends). */
    private fun step(dir: Int, repeat: Boolean) {
        val at = (0 until shown).indexOfFirst { slots[it].view.isFocused }
        val next = at + dir
        if (at >= 0 && next in 0 until shown) {
            removeCallbacks(closeLater)
            slots[next].view.requestFocus()
        } else if (!repeat) {
            close()
        }
    }

    /** Down from the row: the button under it; Up from there: back to the row. Past the top or bottom, closed. */
    private fun vertical(downKey: Boolean, repeat: Boolean) {
        val under = below?.takeIf { belowShown }
        val onUnder = under != null && under.view.isFocused
        when {
            downKey && under != null && !onUnder -> {
                lastInRow = (0 until shown).indexOfFirst { slots[it].view.isFocused }.coerceAtLeast(0)
                removeCallbacks(closeLater)
                under.view.requestFocus()
            }
            !downKey && onUnder -> slots[lastInRow.coerceIn(0, shown - 1)].view.requestFocus()
            !repeat -> close()
        }
    }

    private fun belowSlot(): Slot {
        below?.let { return it }
        val view = rows.base().apply { gravity = Gravity.CENTER }
        val icon = ImageView(context).apply { imageTintList = rows.textColors }
        val text = rows.text("")
        view.addView(icon, LayoutParams(t.dp(18), t.dp(18)))
        view.addView(text)
        val s = Slot(view, icon, text)
        view.setOnClickListener { if (isOpen) s.action?.invoke() }
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(34)).apply { topMargin = t.dp(8) })
        below = s
        return s
    }

    private fun isInside(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === this) return true
            p = p.parent as? View
        }
        return false
    }

    private fun slot(i: Int): Slot {
        slots.getOrNull(i)?.let { return it }
        // The pills of the app's small windows (FireDialog's buttons): white with dark words when selected.
        val view = rows.base().apply { gravity = Gravity.CENTER }
        val icon = ImageView(context).apply { imageTintList = rows.textColors }
        val text = rows.text("")
        view.addView(icon, LayoutParams(t.dp(18), t.dp(18)))
        view.addView(text)
        val s = Slot(view, icon, text)
        view.setOnClickListener { if (isOpen) s.action?.invoke() }
        bar.addView(view, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(34)).apply { if (i > 0) marginStart = t.dp(8) })
        slots += s
        return s
    }

    private fun fill(s: Slot, b: Button) {
        val round = b.icon != null
        s.action = b.onClick
        s.view.visibility = VISIBLE
        s.view.isSelected = b.selected
        s.view.contentDescription = b.label
        if (b.icon != null) s.icon.setImageResource(b.icon) else s.text.text = b.label
        if (s.round == round) return
        s.round = round
        s.icon.visibility = if (round) VISIBLE else GONE
        s.text.visibility = if (round) GONE else VISIBLE
        val lp = s.view.layoutParams as LayoutParams
        lp.width = if (round) t.dp(34) else ViewGroup.LayoutParams.WRAP_CONTENT
        s.view.layoutParams = lp
        style(s)
        if (round) s.view.setPadding(0, 0, 0, 0) else s.view.setPadding(t.dp(14), 0, t.dp(14), 0)
    }

    private fun style(s: Slot) =
        Glass.background(s.view, Glass.Kind.ROW, if (s.round == true) R.drawable.bg_circle else R.drawable.bg_pill)

    private companion object {
        const val OPEN_MS = 160L
        const val CLOSE_MS = 120L
        const val POP_MS = 220L
        const val OPEN_SCALE = 0.94f
        const val CLOSE_SCALE = 0.96f
        const val POP_SCALE = 0.6f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        val OVERSHOOT = OvershootInterpolator(3f)
    }
}

/** Where the small window goes (pure, unit-tested): beside the held row, kept inside the area it may use. */
object HoldPlace {
    /** Its left edge: [gap] right of the anchor's right edge, moved left (over the anchor) when it doesn't fit there. */
    fun x(anchorRight: Float, width: Float, gap: Float, left: Float, right: Float): Float =
        minOf(anchorRight + gap, right - width).coerceAtLeast(left)

    /** Its top: centred on the anchor, moved to stay between [top] and [bottom]. */
    fun y(anchorCenterY: Float, height: Float, top: Float, bottom: Float): Float =
        minOf(anchorCenterY - height / 2, bottom - height).coerceAtLeast(top)
}

/**
 * OK in the guide (pure, unit-tested): a short press acts on its release; held past [holdMs] (or once the system
 * calls it a long press, its first repeat) it's a hold, reported once, and the release after a hold does nothing.
 * A repeat or release of a press that started elsewhere (focus came here while it was held) is not ours.
 */
class PressHold(private val holdMs: Long) {
    private var pressed = false
    private var held = false

    /** A DOWN or one of its repeats. True once, when the press becomes a hold. */
    fun down(repeatCount: Int, longPress: Boolean, heldForMs: Long): Boolean {
        if (repeatCount == 0) {
            pressed = true
            held = false
            return false
        }
        if (!pressed || held) return false
        if (longPress || heldForMs >= holdMs) {
            held = true
            return true
        }
        return false
    }

    /** The release: true when it ends a short press that started here (pick). */
    fun up(canceled: Boolean): Boolean {
        val pick = pressed && !held && !canceled
        cancel()
        return pick
    }

    /** Focus left, or another key came in between: the release that follows does nothing. */
    fun cancel() {
        pressed = false
        held = false
    }
}

/** The groups column's edits from a group's small window (Move to top, Hide category), kept apart to be tested. */
object GroupEdits {
    /** [list] with [id]'s entry first and the others in their order; the same list when it's first already, or absent. */
    fun <T> toTop(list: List<T>, id: String, idOf: (T) -> String): List<T> {
        val i = list.indexOfFirst { idOf(it) == id }
        if (i <= 0) return list
        return ArrayList<T>(list.size).apply {
            add(list[i])
            for (j in list.indices) if (j != i) add(list[j])
        }
    }

    fun toTop(ids: List<String>, id: String): List<String> = toTop(ids, id) { it }

    /**
     * Where the selection goes when [id] leaves [ids] (its playlist's shown groups, in order): the group that takes
     * its place, or the one above when it was the last; null when it was the only one (or isn't there).
     */
    fun afterHide(ids: List<String>, id: String): String? {
        val i = ids.indexOf(id)
        if (i < 0) return null
        return ids.getOrNull(i + 1) ?: ids.getOrNull(i - 1)
    }
}
