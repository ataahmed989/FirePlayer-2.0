package app.fireplayer.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.live.ChannelKey
import app.fireplayer.shell.Tokens
import app.fireplayer.vod.data.SearchHistoryRules
import app.fireplayer.vod.ui.SearchBar
import app.fireplayer.vod.ui.VodSizes

/**
 * Multiview's channel picker ("Add channel" and "Change channel", the user's "add/search, change/search"): Live TV
 * search as the Search page has it, the same bar ([SearchBar], the TV's keyboard on OK) and the same results
 * ([LiveSearchView]: channels, then programmes on them), at the same places, over Multiview on a dark backdrop that
 * leaves the pictures faintly in sight. OK on a channel (or on a programme) picks its channel ([onPick]); Back
 * ([LivePage] passes it on) closes it. Made the first time it's needed; the results are made with the first search.
 */
@SuppressLint("ViewConstructor")
class ChannelPicker(context: Context, private val t: Tokens) : FrameLayout(context) {

    /** OK on a channel, or on a programme (its channel). */
    var onPick: ((ChannelKey) -> Unit)? = null

    private val title = t.label(context, LiveWidths.MV_PICKER_TITLE_SP, t.semibold).apply { gravity = Gravity.CENTER_VERTICAL }
    private val bar = SearchBar(context, t)
    private val note = t.label(context, VodSizes.NOTE)
    private var results: LiveSearchView? = null
    private val x = t.dp(VodSizes.CONTENT_SCREEN_X)

    var isOpen = false
        private set

    init {
        setBackgroundColor(LiveWidths.MV_PICKER_SCRIM)
        visibility = GONE
        clipChildren = false
        clipToPadding = false
        // Where the Search page's kind pills are: what the pick is for.
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.SEARCH_KIND_H)).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.VIEWPORT_TOP)
        })
        // No history here: no trash button beside the bar.
        bar.trash.visibility = GONE
        addView(bar, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.VIEWPORT_TOP + VodSizes.SEARCH_KIND_H + VodSizes.SEARCH_KIND_GAP)
        })
        addView(note, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.searchListTop())
        })
        bar.onQuery = { words -> search(words) }
        bar.onSubmit = { words -> search(words) }
        bar.onArrow = { keyCode, _ -> if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) results?.focusFirst() }
    }

    private fun resultsView(): LiveSearchView = results ?: LiveSearchView(context, t, x).also { v ->
        results = v
        addView(v, indexOfChild(note) + 1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.searchListTop())
        })
        v.onUp = { bar.focusBar() }
        // The first tile stays selected (there's no rail here to go to).
        v.onLeft = {}
        v.onPlay = { key -> if (isOpen) onPick?.invoke(key) }
        v.onGuide = { key, _ -> if (isOpen) onPick?.invoke(key) }
        v.onAnswered = { showNote() }
    }

    /** Opens with [heading] ("Add a channel to Multiview"…), the bar selected and the keyboard up for the first words. */
    fun open(heading: String) {
        title.text = heading
        bar.setText("")
        results?.clear()
        isOpen = true
        visibility = VISIBLE
        // The channels' names are read now, while the first words are typed.
        resultsView().warm()
        showNote()
        bar.showKeyboard()
    }

    /** Closes (the keyboard too); no late answer comes. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        bar.cancel()
        results?.clear()
        // Hidden with the selection in it: Android lets go of the selection (nothing else in full screen takes it).
        visibility = GONE
    }

    fun release() {
        close()
        results?.release()
    }

    fun applyLook() {
        bar.applyLook()
        results?.applyLook()
    }

    private fun search(words: String) {
        if (!isOpen) return
        resultsView().search(SearchHistoryRules.clean(words))
        showNote()
    }

    /** Under the bar: what to do, "Searching…", or that nothing was found (the results show in its place). */
    private fun showNote() {
        val r = results
        val text = when {
            r == null || r.query.isEmpty() -> HINT
            r.hasResults() -> ""
            !r.answered -> VodSizes.SEARCHING_NOTE
            else -> "No channels found for “${r.query}”"
        }
        note.text = text
        note.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val HINT = "Type a channel's name or a programme, then pick the channel"
    }
}
