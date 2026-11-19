package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import app.fireplayer.vod.Card
import app.fireplayer.vod.Title

/**
 * A page's banner (216 dp; My List's 186): the title's picture, sharp ([HeaderArt]), under a separate dark shading
 * ([Shade]: strongest behind the words on the left, easing out towards the right, with a little more along the
 * bottom), and on it the title (40 sp; a long name a size smaller), a facts line (12.5 sp: year, genres, rating,
 * length), two lines of story, the Watch now and Details buttons ([actions]) and, centred near the bottom, dots for
 * the rotation. A fine outline is drawn over the picture ([Glass.edge]).
 *
 * While idle it goes through the page's feed (Top 10, or Popular's first 10) about every 8 s ([HeaderRotation]), the
 * text fading out and in and the picture crossfading; while focus is in the page's Continue row it shows the chosen
 * card's title instead: the words at once, the picture half a second later (so a held D-pad decodes nothing). While
 * one of its buttons has focus it stays on the title it shows. Its text views have fixed sizes, so new words only
 * redraw them. The buttons' keys go to the page ([Listener]).
 */
@SuppressLint("ViewConstructor")
class VodHeader(
    context: Context,
    private val t: Tokens,
    private val cardW: Float,
    private val heightDp: Float = VodSizes.HEADER_H,
    /** Whether it has the Watch now and Details buttons (My List's follows the chosen poster and has none). */
    private val actions: Boolean = true,
) : FrameLayout(context) {

    enum class Button { WATCH, DETAILS }

    interface Listener {
        /** Focus came to the banner's buttons, or left them altogether. */
        fun onHeaderFocus(has: Boolean)
        fun onHeaderKey(button: Button, keyCode: Int, event: KeyEvent): Boolean
        /** The banner has no title left to show while one of its buttons holds focus: focus must move on first. */
        fun onHeaderEmptied()
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val rotation = HeaderRotation()
    private var feed: List<Title> = emptyList()
    private var following: Card? = null

    /** Whether anything else (a window, the settings window) asks it to hold still; asked at each turn. */
    var blocked: () -> Boolean = { false }

    /** The picture fills the card (Home), or is a box at its right whose left edge is eased away (the wider cards). */
    private val whole = VodSizes.backdropW(cardW) >= cardW
    private val art = HeaderArt(context, HeaderArt.ArtSpec.BANNER).apply { if (!whole) leftFade = VodSizes.BANNER_FADE_LEFT_TO }
    private val shade = Shade(context)

    /** Fades as one without an offscreen layer: its texts don't overlap. */
    private val words = object : LinearLayout(context) {
        override fun hasOverlappingRendering() = false
    }.apply { orientation = LinearLayout.VERTICAL }

    private val textW = t.dp(cardW - 2 * VodSizes.HEADER_INSET)
    private val title = TitleLine(context, t)
    private val facts = t.label(context, VodSizes.FACTS)
    private val story = t.label(context, VodSizes.STORY).apply {
        // Always two lines, in a box two lines tall (see init): new words never change its height, so they're only
        // redrawn (a wrapped height would lay the page out again whenever one line became two).
        setLines(VodSizes.HEADER_STORY_LINES)
    }
    private val pillSpec = Buttons.PillSpec(
        height = VodSizes.BUTTON_H,
        pad = VodSizes.BUTTON_PAD,
        text = VodSizes.BUTTON,
        icon = VodSizes.BUTTON_ICON,
        iconGap = VodSizes.BUTTON_ICON_GAP,
    )
    private val watch = Buttons.ActionPill(context, t, pillSpec).apply { set(WATCH_NOW, R.drawable.ic_play) }
    private val details = Buttons.ActionPill(context, t, pillSpec).apply { set(DETAILS, null) }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        visibility = INVISIBLE
    }
    private val dots = Dots(context, t)
    /** What the words show now: the buttons act on it. */
    private var shown: Title? = null

    init {
        clipChildren = true
        addView(art)
        addView(shade)
        val shadow = t.dpf(VodSizes.HEADER_TEXT_SHADOW)
        val shadowDy = t.dpf(VodSizes.HEADER_TEXT_SHADOW_DY)
        facts.setShadowLayer(shadow, 0f, shadowDy, VodSizes.HEADER_TEXT_SHADOW_COLOR)
        story.setShadowLayer(shadow, 0f, shadowDy, VodSizes.HEADER_TEXT_SHADOW_COLOR)
        words.addView(title, LinearLayout.LayoutParams(textW, t.dp(VodSizes.HEADER_TITLE_H)))
        words.addView(facts, LinearLayout.LayoutParams(textW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.HEADER_FACTS_GAP) })
        words.addView(story, LinearLayout.LayoutParams(t.dp(VodSizes.storyW(cardW)), story.lineHeight * VodSizes.HEADER_STORY_LINES).apply { topMargin = t.dp(VodSizes.HEADER_STORY_GAP) })
        // The words stand on the buttons (or, without them, on the card's bottom margin): whatever the card's height.
        addView(words, LayoutParams(textW, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = t.dp(VodSizes.HEADER_INSET)
            bottomMargin = t.dp(if (actions) VodSizes.headerWordsBottom() else VodSizes.HEADER_BUTTONS_BOTTOM)
        })
        if (actions) {
            buttons.addView(watch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.BUTTON_H)))
            buttons.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.BUTTON_H)).apply {
                leftMargin = t.dp(VodSizes.BUTTON_GAP)
            })
            addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, t.dp(VodSizes.BUTTON_H)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = t.dp(VodSizes.HEADER_INSET)
                bottomMargin = t.dp(VodSizes.HEADER_BUTTONS_BOTTOM)
            })
            for ((which, pill) in listOf(Button.WATCH to watch, Button.DETAILS to details)) {
                Tokens.focusEffect(pill, Tokens.GROW_PILL, null) { has -> onButtonFocus(has) }
                pill.setOnKeyListener { _, keyCode, event -> listener?.onHeaderKey(which, keyCode, event) ?: false }
            }
        }
        addView(dots, LayoutParams(t.dp(VodSizes.DOTS_W), t.dp(VodSizes.DOTS_H)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = t.dp(VodSizes.DOTS_BOTTOM)
        })
        applyLook()
    }

    /**
     * The look's surface: a glass panel with its outline drawn over the picture, or the classic dark one with the
     * picture 1 dp inside its border. The picture and the shading follow the card's corners.
     */
    fun applyLook() {
        Glass.background(this, Glass.Kind.PANEL)
        Glass.edge(this, Glass.Kind.PANEL)
        val inset = if (Glass.on) 0 else t.dp(VodSizes.HEADER_ART_INSET)
        val r = Glass.cornerRadius(context, Glass.Kind.PANEL, t.dp(cardW), t.dp(heightDp)) - inset
        art.radius = r
        shade.radius = r
        val h = t.dp(heightDp) - 2 * inset
        place(art, t.dp(VodSizes.backdropW(cardW)) - (if (whole) 2 else 1) * inset, h, inset)
        place(shade, t.dp(cardW) - 2 * inset, h, inset)
        watch.applyLook()
        details.applyLook()
    }

    /** [v] at the card's right, [inset] inside its top and right edges; laid out again only when that changes. */
    private fun place(v: View, w: Int, h: Int, inset: Int) {
        val lp = v.layoutParams as LayoutParams
        if (lp.width == w && lp.height == h && lp.topMargin == inset && lp.rightMargin == inset && lp.gravity == (Gravity.END or Gravity.TOP)) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.END or Gravity.TOP
        lp.topMargin = inset
        lp.rightMargin = inset
        v.layoutParams = lp
    }

    // ---- The buttons ---------------------------------------------------------------------------------------------

    /** The title the banner shows, as a card (the Continue card it follows, with its episode): what its buttons act on. */
    val shownCard: Card? get() = following?.takeIf { it.title.id == shown?.id } ?: shown?.let { Card(it) }

    /** Whether one of the buttons has focus. */
    val hasButtonFocus: Boolean get() = watch.isFocused || details.isFocused

    /** Focus to [button]; false when the banner shows no title (or has no buttons). */
    fun focusButton(button: Button): Boolean {
        if (!actions || shown == null || buttons.visibility != VISIBLE) return false
        return (if (button == Button.WATCH) watch else details).requestFocus()
    }

    private val buttonsLeft = Runnable {
        if (hasButtonFocus) return@Runnable
        rotation.unhold(now())
        schedule()
        listener?.onHeaderFocus(false)
    }

    private fun onButtonFocus(has: Boolean) {
        main.removeCallbacks(buttonsLeft)
        if (!has) {
            // To the other button: that one takes over at once. Elsewhere: the rotation's full wait starts again.
            main.post(buttonsLeft)
            return
        }
        if (rotation.held) return
        // The title shown stays while a button has focus: a turn on its way is dropped.
        rotation.hold()
        main.removeCallbacks(turn)
        main.removeCallbacks(warmNext)
        listener?.onHeaderFocus(true)
    }

    // ---- The feed and the title followed -----------------------------------------------------------------------------

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
            // Unless the banner has turned to something else meanwhile (a card followed, a button focused).
            if (following == null) showText(feed.getOrNull(rotation.index), null)
            words.animate().alpha(1f).setDuration(TEXT_FADE_MS).start()
        }.start()
    }

    private fun showText(title: Title?, caption: String?) {
        words.animate().cancel()
        words.alpha = 1f
        shown = title
        this.title.set(title?.name.orEmpty())
        facts.text = title?.let { RowRules.facts(it, caption) }.orEmpty()
        story.text = title?.description.orEmpty()
        // No title, no buttons (and nothing for focus to land on); focus on one moves on before they hide (a hidden
        // view holding focus would hand it anywhere).
        if (actions) {
            if (title == null && hasButtonFocus) listener?.onHeaderEmptied()
            buttons.visibility = if (title == null) INVISIBLE else VISIBLE
        }
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun updateDots() {
        dots.set(feed.size, rotation.index)
        dots.visibility = if (following == null && feed.size > 1) VISIBLE else INVISIBLE
    }

    /**
     * The banner's name: one line in its fixed box, drawn here so a name of another size is only redrawn. It's the
     * large size unless the name is too wide for the box at that size; then a size smaller, cut short if it must be.
     */
    private class TitleLine(context: Context, private val t: Tokens) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = t.faceOf(VodSizes.HEADER_TITLE.weight)
            color = t.colorOf(VodSizes.HEADER_TITLE)
            setShadowLayer(t.dpf(VodSizes.HEADER_TEXT_SHADOW), 0f, t.dpf(VodSizes.HEADER_TEXT_SHADOW_DY), VodSizes.HEADER_TEXT_SHADOW_COLOR)
        }
        private val scaled = resources.displayMetrics.scaledDensity
        private var name = ""
        private var line: CharSequence = ""
        private var fittedFor = -1
        /** Both sizes stand on the large size's baseline. */
        private val baselineUp: Float

        init {
            paint.textSize = VodSizes.HEADER_TITLE_SP * scaled
            baselineUp = paint.descent()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun set(text: String) {
            if (text == name) return
            name = text
            fittedFor = -1
            invalidate()
        }

        private fun fit() {
            fittedFor = width
            val room = width.toFloat().coerceAtLeast(1f)
            paint.textSize = VodSizes.HEADER_TITLE_SP * scaled
            paint.textSize = VodSizes.headerTitleSp(paint.measureText(name), room) * scaled
            line = TextUtils.ellipsize(name, paint, room, TextUtils.TruncateAt.END)
        }

        override fun onDraw(canvas: Canvas) {
            if (name.isEmpty() || width <= 0) return
            if (fittedFor != width) fit()
            canvas.drawText(line, 0, line.length, 0f, height - baselineUp, paint)
        }
    }

    /**
     * The banner's dark shading, a layer of its own over the picture (which stays sharp and whole under it): from the
     * left, strongest behind the words and easing out towards the right, and a second one rising towards the bottom
     * ([VodSizes.HEADER_SHADE_ALPHAS], [VodSizes.HEADER_SHADE_BOTTOM_ALPHAS]), in the glass style's window colour.
     * Both ease out to nothing, so neither ends in a line. Its shaders are made once per size; it never moves.
     */
    private class Shade(context: Context) : View(context) {
        var radius = 0f
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }
        private val across = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val below = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val box = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            box.set(0f, 0f, w.toFloat(), h.toFloat())
            val rgb = GlassStyle.WINDOW_TINT and 0x00FFFFFF
            across.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                IntArray(VodSizes.HEADER_SHADE_ALPHAS.size) { (VodSizes.HEADER_SHADE_ALPHAS[it] shl 24) or rgb }, null, Shader.TileMode.CLAMP,
            )
            below.shader = LinearGradient(
                0f, h * VodSizes.HEADER_SHADE_BOTTOM_FROM, 0f, h.toFloat(),
                IntArray(VodSizes.HEADER_SHADE_BOTTOM_ALPHAS.size) { (VodSizes.HEADER_SHADE_BOTTOM_ALPHAS[it] shl 24) or rgb }, null, Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0) return
            canvas.drawRoundRect(box, radius, radius, across)
            canvas.drawRoundRect(box, radius, radius, below)
        }
    }

    /** Draws the rotation's dots, centred: the current one a 14 dp white bar, the others 5 dp dots at 35% white. */
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
            val gap = t.dpf(VodSizes.DOT_GAP)
            val bar = t.dpf(VodSizes.DOT_BAR)
            var x = (width - (bar + (count - 1) * (h + gap))) / 2f
            for (i in 0 until count) {
                val w = if (i == index) bar else h
                paint.color = if (i == index) VodSizes.DOT_ON else VodSizes.DOT_OFF
                r.set(x, 0f, x + w, h)
                canvas.drawRoundRect(r, h / 2, h / 2, paint)
                x += w + gap
            }
        }
    }

    companion object {
        private const val MAX_FEED = 10
        private const val TEXT_FADE_MS = VodSizes.HEADER_TEXT_FADE_MS
        private const val FOLLOW_ART_MS = VodSizes.FOLLOW_ART_MS
        private const val WARM_AHEAD_MS = VodSizes.WARM_AHEAD_MS
        private const val WATCH_NOW = "Watch now"
        private const val DETAILS = "Details"
    }
}
