package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Rows
import app.fireplayer.vod.ui.TitleRules.Dir

/**
 * The title page's action column (Design-References/redesign-2026-11-19; design-brief.md §6.4 for what each does),
 * top to bottom: Play / Resume (its words carry the state, a thin progress line inside when it resumes), Play from
 * beginning (only with a resume point), Sources, then the round (+) My List (a blue ✓ while the title is on it) and
 * the round (⋯) side by side. The pills are as wide as the column, their words centred, a size smaller when they're
 * long, and cut short only if they still don't fit. Buttons a title doesn't have are GONE, so the others close up; Play that can't play yet ("Coming Mar 6") stays,
 * dimmed and passed by. Made from the app's shared buttons ([Buttons]: glass pills and circles, blue on white when
 * focused, growing 1.04 and 1.08).
 *
 * Up and Down move between the rows, Left and Right between the circles, without wrapping ([TitleRules.buttonStep]);
 * a press that leaves the column is the page's ([Listener]: Left to the rail, Right to what's beside the column, Down
 * to what's under it). OK acts on its release, and only for a press that started here.
 */
@SuppressLint("ViewConstructor")
class TitleButtons(context: Context, private val t: Tokens, private val listener: Listener) : LinearLayout(context) {

    enum class Which { PLAY, RESTART, SOURCES, MY_LIST, MORE }

    interface Listener {
        fun onButton(which: Which)
        fun onButtonFocused(which: Which)
        /** Down from the column's last row. */
        fun onButtonsDown(which: Which)
        /** Right out of the column. */
        fun onButtonsRight(which: Which)
        fun onLeftEdge()
    }

    private val spec = Buttons.PillSpec(
        height = VodSizes.BUTTON_H,
        pad = VodSizes.BUTTON_PAD,
        text = VodSizes.BUTTON,
        icon = VodSizes.BUTTON_ICON,
        iconGap = VodSizes.BUTTON_ICON_GAP,
        progressH = VodSizes.PILL_PROGRESS_H,
        progressBottom = VodSizes.PILL_PROGRESS_BOTTOM,
    )

    val play = pill()
    private val restart = pill()
    private val sources = pill()
    private val myList = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON, toggle = true)
    private val more = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON).apply { set(R.drawable.ic_more, "More") }
    private val circles = LinearLayout(context).apply {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    /** Each button's focusable view, in order. */
    private val order: List<Pair<Which, View>> = listOf(
        Which.PLAY to play, Which.RESTART to restart, Which.SOURCES to sources,
        Which.MY_LIST to myList.button, Which.MORE to more.button,
    )

    /** The button focus was last on (coming back into the column lands on it). */
    var last = Which.PLAY
        private set

    private var armed: View? = null

    init {
        orientation = VERTICAL
        // Glass pads, a grown pill and the circles' names draw outside the views.
        clipChildren = false
        clipToPadding = false
        val h = t.dp(VodSizes.BUTTON_H)
        val gap = t.dp(VodSizes.BUTTON_GAP)
        listOf<View>(play, restart, sources).forEachIndexed { i, v ->
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, h).apply { if (i > 0) topMargin = gap })
        }
        circles.addView(myList, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        circles.addView(more, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = gap })
        addView(circles, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        for ((which, v) in order) {
            if (v is Buttons.ActionPill) Tokens.focusEffect(v, Tokens.GROW_PILL, null) { has -> if (has) focused(which) }
            else (v.parent as? Buttons.RoundButton)?.let { rb ->
                Tokens.focusEffect(v, Tokens.GROW_CIRCLE, rb.label) { has -> if (has) focused(which) }
            }
            v.setOnKeyListener { _, keyCode, event -> onKey(which, v, keyCode, event) }
        }
        setMyList(false)
        words(play, "Play", R.drawable.ic_play)
        words(restart, "Play from beginning", R.drawable.ic_restart)
        words(sources, "Sources", null)
    }

    /** A pill of the column: its icon and words centred, the words on one line and cut short if the column is too narrow. */
    private fun pill() = Buttons.ActionPill(context, t, spec).apply {
        gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * [pill]'s words and icon: at the buttons' size, or a size down when they're wider than the column has room for
     * (measured here; the size changes only when the words cross that width).
     */
    private fun words(pill: Buttons.ActionPill, text: String, iconRes: Int?) {
        pill.set(text, iconRes)
        val paint = pill.label.paint
        val now = paint.textSize
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, VodSizes.BUTTON.sp, resources.displayMetrics)
        val wide = paint.measureText(text) > t.dpf(VodSizes.pillTextW(iconRes != null))
        paint.textSize = now
        pill.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (wide) VodSizes.BUTTON_LONG.sp else VodSizes.BUTTON.sp)
    }

    private fun focused(which: Which) {
        last = which
        listener.onButtonFocused(which)
    }

    /** Play as the title's state says it (kept while the pill shows a wait, [setWaiting]). */
    private var playState = TitleRules.Play("Play")
    private var waiting = false

    /** Play's words, progress line and whether it can play now (dimmed and passed by when not). */
    internal fun setPlay(p: TitleRules.Play) {
        playState = p
        if (waiting) {
            play.isFocusable = p.enabled
            return
        }
        words(play, p.label, R.drawable.ic_play)
        play.progress = p.progress
        play.alpha = if (p.enabled) 1f else Rows.DIM
        if (!p.enabled && play.isFocused) focusFirst(skip = play)
        play.isFocusable = p.enabled
    }

    /**
     * Play waiting for the add-ons (design-brief.md §6.5, stage 1): "Waiting for MediaFusion · 9 s" with a thin bar
     * toward its answer limit; null [label] puts Play back as the title's state says.
     */
    fun setWaiting(label: String?, fraction: Float = 0f) {
        if (label == null) {
            if (!waiting) return
            waiting = false
            setPlay(playState)
            return
        }
        waiting = true
        words(play, label, null)
        play.progress = fraction
    }

    /** The Sources button's words: "Sources", then "Sources · 40" once an add-on has answered. */
    fun setSourcesLabel(text: String) {
        if (sourcesText == text) return
        sourcesText = text
        words(sources, text, null)
    }

    private var sourcesText = "Sources"

    /** Play from beginning: only with a saved position. */
    fun setRestart(shown: Boolean) = show(restart, Which.RESTART, shown)

    /** Sources: not for a film that isn't out, nor a show with nothing aired yet. */
    fun setSources(shown: Boolean) = show(sources, Which.SOURCES, shown)

    /** My List: (+), or a blue ✓ while the title is on it. */
    fun setMyList(on: Boolean) {
        myList.set(if (on) R.drawable.ic_check else R.drawable.ic_add, if (on) "In My List" else "My List")
        myList.setOn(on)
    }

    /** The (⋯) button: the small window opens beside it. */
    val moreButton: View get() = more.button

    private fun show(v: View, which: Which, shown: Boolean) {
        val vis = if (shown) VISIBLE else GONE
        if (v.visibility == vis) return
        // A button that goes while it has focus hands it to Play (or the first that can take it) first.
        if (!shown && v.hasFocus()) {
            last = Which.PLAY
            focusFirst(skip = v)
        }
        v.visibility = vis
        if (!shown && last == which) last = Which.PLAY
    }

    /** The button focus was last on, else the first that can take it. */
    fun focusLast(): Boolean {
        val v = viewOf(last)
        if (Buttons.canFocus(v) && v.requestFocus()) return true
        return focusFirst()
    }

    /** Play, or the first button that can take focus (a film not out yet: (+)); never [skip]. */
    fun focusFirst(skip: View? = null): Boolean {
        for ((_, v) in order) {
            if (v === skip) continue
            if (Buttons.canFocus(v) && v.requestFocus()) return true
        }
        return false
    }

    /** Focus on [which] if it can take it; otherwise as [focusLast]. */
    fun focus(which: Which): Boolean {
        last = which
        return focusLast()
    }

    /** The button holding focus, or null. */
    fun focusedButton(): Which? = order.firstOrNull { it.second.isFocused }?.first

    fun viewOf(which: Which): View = order.first { it.first == which }.second

    fun applyLook() {
        play.applyLook()
        restart.applyLook()
        sources.applyLook()
        myList.applyLook()
        more.applyLook()
    }

    private fun onKey(which: Which, v: View, keyCode: Int, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // On release, and only for a press that started here (not the OK that opened the page).
                if (down && event.repeatCount == 0) armed = v
                if (!down && event.action == KeyEvent.ACTION_UP) {
                    val mine = armed === v && !event.isCanceled
                    armed = null
                    if (mine) listener.onButton(which)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            else -> return false
        }
        if (!down) return true
        armed = null
        val next = TitleRules.buttonStep(which, dir) { Buttons.canFocus(viewOf(it)) }
        when {
            next != null -> viewOf(next).requestFocus()
            dir == Dir.LEFT -> listener.onLeftEdge()
            dir == Dir.RIGHT -> listener.onButtonsRight(which)
            dir == Dir.DOWN -> listener.onButtonsDown(which)
        }
        return true
    }
}
