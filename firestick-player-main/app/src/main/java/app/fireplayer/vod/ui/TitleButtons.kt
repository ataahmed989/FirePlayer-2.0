package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Rows

/**
 * The title page's row of buttons, left to right (design-brief.md §6.4): Play / Resume (its words carry the state, a
 * thin progress line inside when it resumes), Play from beginning, Episodes (shows), Sources, the round (+) My List (a
 * blue ✓ while the title is on it) and the round (⋯). Buttons a title doesn't have are GONE, so the others close up;
 * Play that can't play yet ("Coming Mar 6") stays, dimmed and passed by. Made from the app's shared buttons
 * ([Buttons]: glass pills and circles, blue on white when focused, growing 1.04 and 1.08).
 *
 * Left and Right move between the buttons that can take focus, without wrapping (Left from the first goes to the rail,
 * [Listener.onLeftEdge]); Up does nothing; Down and OK are the page's ([Listener]). OK acts on its release, and only
 * for a press that started here.
 */
@SuppressLint("ViewConstructor")
class TitleButtons(context: Context, private val t: Tokens, private val listener: Listener) : LinearLayout(context) {

    enum class Which { PLAY, RESTART, EPISODES, SOURCES, MY_LIST, MORE }

    interface Listener {
        fun onButton(which: Which)
        fun onButtonFocused(which: Which)
        fun onButtonsDown(which: Which)
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

    val play = Buttons.ActionPill(context, t, spec)
    private val restart = Buttons.ActionPill(context, t, spec).apply { set("Play from beginning", R.drawable.ic_restart) }
    private val episodes = Buttons.ActionPill(context, t, spec).apply { set("Episodes", R.drawable.ic_episodes) }
    private val sources = Buttons.ActionPill(context, t, spec).apply { set("Sources", null) }
    private val myList = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON, toggle = true)
    private val more = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON).apply { set(R.drawable.ic_more, "More") }

    /** Each button's focusable view, in order. */
    private val order: List<Pair<Which, View>> = listOf(
        Which.PLAY to play, Which.RESTART to restart, Which.EPISODES to episodes, Which.SOURCES to sources,
        Which.MY_LIST to myList.button, Which.MORE to more.button,
    )

    /** The button focus was last on (Up from the rows comes back to it). */
    var last = Which.PLAY
        private set

    private var armed: View? = null

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
        // The circles' names hang under them: room for them, without moving the row.
        val h = t.dp(VodSizes.BUTTON_H)
        listOf<View>(play, restart, episodes, sources).forEachIndexed { i, v ->
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, h).apply { if (i > 0) leftMargin = t.dp(VodSizes.BUTTON_GAP) })
        }
        for (v in listOf<View>(myList, more)) {
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(VodSizes.BUTTON_GAP) })
        }
        for ((which, v) in order) {
            if (v is Buttons.ActionPill) Tokens.focusEffect(v, Tokens.GROW_PILL, null) { has -> if (has) focused(which) }
            else (v.parent as? Buttons.RoundButton)?.let { rb ->
                Tokens.focusEffect(v, Tokens.GROW_CIRCLE, rb.label) { has -> if (has) focused(which) }
            }
            v.setOnKeyListener { _, keyCode, event -> onKey(which, v, keyCode, event) }
        }
        setMyList(false)
        play.set("Play", R.drawable.ic_play)
    }

    private fun focused(which: Which) {
        last = which
        listener.onButtonFocused(which)
    }

    /** Play's words, progress line and whether it can play now (dimmed and passed by when not). */
    internal fun setPlay(p: TitleRules.Play) {
        play.set(p.label, R.drawable.ic_play)
        play.progress = p.progress
        play.alpha = if (p.enabled) 1f else Rows.DIM
        if (!p.enabled && play.isFocused) focusFirst(skip = play)
        play.isFocusable = p.enabled
    }

    /** Play from beginning: only with a saved position. */
    fun setRestart(shown: Boolean) = show(restart, Which.RESTART, shown)

    /** Episodes: shows only, once their episodes are known. */
    fun setEpisodes(shown: Boolean) = show(episodes, Which.EPISODES, shown)

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
        episodes.applyLook()
        sources.applyLook()
        myList.applyLook()
        more.applyLook()
    }

    private fun onKey(which: Which, v: View, keyCode: Int, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (keyCode) {
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
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    armed = null
                    step(which, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) {
                    armed = null
                    listener.onButtonsDown(which)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) armed = null
                return true
            }
        }
        return false
    }

    /** The next or previous button that can take focus; none past the ends (Left past the first: the rail). */
    private fun step(from: Which, dir: Int) {
        val at = order.indexOfFirst { it.first == from }
        var i = at + dir
        while (i in order.indices) {
            val v = order[i].second
            if (Buttons.canFocus(v)) {
                v.requestFocus()
                return
            }
            i += dir
        }
        if (dir < 0) listener.onLeftEdge()
    }
}
