package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.vod.Card
import app.fireplayer.vod.Title

/**
 * A page's header card (195 dp): the backdrop on the right ([HeaderArt]), and on the left the title (34 sp), a facts
 * line (12.5 sp: year, genres, rating, length) and two lines of story, with dots for the rotation. While idle it goes
 * through the page's feed (Top 10, or Popular's first 10) about every 8 s ([HeaderRotation]), the text fading out and
 * in and the picture crossfading; while focus is in the page's Continue row it shows the chosen card's title instead:
 * the words at once, the picture half a second later (so a held D-pad decodes nothing). Its text views have fixed sizes,
 * so new words only redraw them.
 */
@SuppressLint("ViewConstructor")
class VodHeader(context: Context, private val t: Tokens, private val cardW: Float) : FrameLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val rotation = HeaderRotation()
    private var feed: List<Title> = emptyList()
    private var following: Card? = null

    /** Whether anything else (a window, the settings window) asks it to hold still; asked at each turn. */
    var blocked: () -> Boolean = { false }

    private val art = HeaderArt(context)

    /** Fades as one without an offscreen layer: its texts don't overlap. */
    private val words = object : LinearLayout(context) {
        override fun hasOverlappingRendering() = false
    }.apply { orientation = LinearLayout.VERTICAL }

    private val textW = t.dp(cardW - 2 * VodSizes.HEADER_INSET)
    private val title = t.label(context, VodSizes.HEADER_TITLE)
    private val facts = t.label(context, VodSizes.FACTS)
    private val story = t.label(context, VodSizes.STORY).apply {
        // At most two lines, in a box two lines tall (see init): new words never change its height, so they're only
        // redrawn (a wrapped height would lay the page out again whenever one line became two).
        setLines(2)
    }
    private val dots = Dots(context, t)

    init {
        clipChildren = true
        val artW = t.dp(VodSizes.backdropW(cardW))
        val inset = t.dp(VodSizes.HEADER_ART_INSET)
        addView(art, LayoutParams(artW - inset, t.dp(VodSizes.HEADER_H) - 2 * inset).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.TOP
            topMargin = inset
            rightMargin = inset
        })
        words.addView(title, LinearLayout.LayoutParams(textW, LinearLayout.LayoutParams.WRAP_CONTENT))
        words.addView(facts, LinearLayout.LayoutParams(textW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.HEADER_FACTS_GAP) })
        words.addView(story, LinearLayout.LayoutParams(t.dp(VodSizes.storyW(cardW)), story.lineHeight * 2).apply { topMargin = t.dp(VodSizes.HEADER_STORY_GAP) })
        addView(words, LayoutParams(textW, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = t.dp(VodSizes.HEADER_INSET)
            topMargin = t.dp(VodSizes.headerTextTop())
        })
        addView(dots, LayoutParams(t.dp(VodSizes.DOTS_W), t.dp(VodSizes.DOTS_H)).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            leftMargin = t.dp(VodSizes.HEADER_INSET)
            bottomMargin = t.dp(VodSizes.DOTS_BOTTOM)
        })
        applyLook()
    }

    /** The look's surface: a glass panel, or the classic dark one; the picture follows its corners, 1 dp inside. */
    fun applyLook() {
        Glass.background(this, Glass.Kind.PANEL)
        val r = Glass.cornerRadius(context, Glass.Kind.PANEL, t.dp(cardW), t.dp(VodSizes.HEADER_H))
        art.radius = r - t.dpf(VodSizes.HEADER_ART_INSET)
    }

    /** The page's idle titles (up to 10). The title on screen stays when it's still among them. */
    fun setFeed(titles: List<Title>) {
        val list = titles.take(MAX_FEED)
        if (list.map { it.id } == feed.map { it.id }) {
            feed = list
            return
        }
        val showing = feed.getOrNull(rotation.index)?.id
        feed = list
        rotation.setFeed(list.size, now(), keepIndex = false)
        val keep = list.indexOfFirst { it.id == showing }
        if (keep > 0) rotation.jumpTo(keep, now())
        if (following == null) showFeedTitle(fadeText = false)
        updateDots()
        schedule()
    }

    /** Focus is on [card] in the Continue row: its title shows, the picture after half a second. */
    fun follow(card: Card) {
        following = card
        rotation.follow()
        main.removeCallbacks(turn)
        main.removeCallbacks(followArt)
        showText(card.title, card.caption)
        updateDots()
        main.postDelayed(followArt, FOLLOW_ART_MS)
    }

    /** Focus left the Continue row: back to the feed's title, the wait for the next one starting again. */
    fun unfollow() {
        if (following == null) return
        following = null
        main.removeCallbacks(followArt)
        rotation.unfollow(now())
        showFeedTitle(fadeText = false)
        updateDots()
        schedule()
    }

    fun pause() {
        rotation.pause()
        main.removeCallbacks(turn)
        main.removeCallbacks(followArt)
        // The next picture isn't decoded ahead for a hidden, stopped or closed page.
        main.removeCallbacks(warmNext)
    }

    fun resume() {
        if (!rotation.paused) return
        rotation.resume(now())
        // The picture let go while hidden comes back.
        val c = following
        if (c != null) art.show(c.title.background) else feed.getOrNull(rotation.index)?.let { art.show(it.background) }
        schedule()
    }

    /** Lets the pictures go (the page is hidden or stopped). */
    fun release() {
        pause()
        art.release()
    }

    private val followArt = Runnable { following?.let { art.show(it.title.background) } }

    private val turn = Runnable {
        if (blocked()) {
            // A window over the page: try again a turn later.
            rotation.restart(now())
            schedule()
            return@Runnable
        }
        if (rotation.advanceIfDue(now())) {
            showFeedTitle(fadeText = true)
            updateDots()
        }
        schedule()
    }

    private val warmNext = Runnable {
        val next = rotation.nextIndex()
        if (next >= 0) art.warm(feed.getOrNull(next)?.background)
    }

    private fun schedule() {
        main.removeCallbacks(turn)
        main.removeCallbacks(warmNext)
        val wait = rotation.delayFrom(now())
        if (wait < 0) return
        main.postDelayed(turn, wait)
        // The next picture decoded ahead, so the switch never waits on it.
        main.postDelayed(warmNext, (wait - WARM_AHEAD_MS).coerceAtLeast(0))
    }

    private fun showFeedTitle(fadeText: Boolean) {
        val title = feed.getOrNull(rotation.index)
        if (title == null) {
            showText(null, null)
            art.show(null)
            return
        }
        // Off screen (paused), the picture waits: [resume] shows the title's then.
        if (!rotation.paused) art.show(title.background)
        if (!fadeText) {
            showText(title, null)
            return
        }
        words.animate().cancel()
        words.animate().alpha(0f).setDuration(TEXT_FADE_MS).withEndAction {
            showText(feed.getOrNull(rotation.index), null)
            words.animate().alpha(1f).setDuration(TEXT_FADE_MS).start()
        }.start()
    }

    private fun showText(title: Title?, caption: String?) {
        words.animate().cancel()
        words.alpha = 1f
        this.title.text = title?.name.orEmpty()
        facts.text = title?.let { RowRules.facts(it, caption) }.orEmpty()
        story.text = title?.description.orEmpty()
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun updateDots() {
        dots.set(feed.size, rotation.index)
        dots.visibility = if (following == null && feed.size > 1) VISIBLE else INVISIBLE
    }

    /** Draws the rotation's dots: the current one a 16 dp white bar, the others 6 dp dots at 35% white. */
    private class Dots(context: Context, private val t: Tokens) : View(context) {
        private var count = 0
        private var index = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun set(count: Int, index: Int) {
            if (this.count == count && this.index == index) return
            this.count = count
            this.index = index
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (count <= 1) return
            val h = height.toFloat()
            var x = 0f
            for (i in 0 until count) {
                val w = if (i == index) t.dpf(VodSizes.DOT_BAR) else h
                paint.color = if (i == index) VodSizes.DOT_ON else VodSizes.DOT_OFF
                r.set(x, 0f, x + w, h)
                canvas.drawRoundRect(r, h / 2, h / 2, paint)
                x += w + t.dpf(VodSizes.DOT_GAP)
            }
        }
    }

    companion object {
        private const val MAX_FEED = 10
        private const val TEXT_FADE_MS = VodSizes.HEADER_TEXT_FADE_MS
        private const val FOLLOW_ART_MS = VodSizes.FOLLOW_ART_MS
        private const val WARM_AHEAD_MS = VodSizes.WARM_AHEAD_MS
    }
}
