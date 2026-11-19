package app.fireplayer.shell

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassDrawable
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.ListMath

/**
 * The left tab rail: the one FirePlayer brand mark, then the tabs, Settings' gear at the bottom.
 *
 * On Home, Movies, TV Shows, My List, Search and Settings it is a narrow floating window: inset from the screen's left,
 * top and bottom, with rounded corners, a fine outline and a soft shadow. On Live TV it is a strip attached to the
 * screen's left edge, full height and square, with a hairline down its right edge; it moves between the two as Live TV
 * is entered or left ([setAttached]: the same drawing stretched and faded, nothing laid out again on the way).
 *
 * It sits collapsed (icons only) while the page has focus and widens with labels, and a larger mark and name, when
 * focus comes into it: the same window or strip, drawn over the page, so the page (and live video) never moves or
 * changes size. Only tabs that work are shown; the others are added as they're built (no placeholders).
 *
 * Its view is always the screen's full height and starts at its left edge; the window is drawn inside it ([Surface]).
 * Classic: the classic dark fill, solid. Glass: the shared dark glass ([GlassStyle]); narrow, over the still wallpaper,
 * it is the tint alone, and widened over the page it shows the page behind it blurred ([Glass.overlayDrawable]).
 */
class NavRail(context: Context, private val t: Tokens) : LinearLayout(context) {

    enum class Tab(val label: String, val icon: Int) {
        SEARCH("Search", R.drawable.ic_search_rail),
        HOME("Home", R.drawable.ic_home_rail),
        MOVIES("Movies", R.drawable.ic_movies),
        SHOWS("TV Shows", R.drawable.ic_tv_shows),
        MY_LIST("My List", R.drawable.ic_my_list),
        LIVE("Live TV", R.drawable.ic_live),
        SETTINGS("Settings", R.drawable.ic_settings),
    }

    var onTab: ((Tab) -> Unit)? = null
    /** Right from the rail: into the page. */
    var onEnterPage: (() -> Unit)? = null

    private val brand: LinearLayout
    private val brandMark: ImageView
    private val brandText: TextView
    private val items = LinkedHashMap<Tab, LinearLayout>()
    private val labels = ArrayList<TextView>()
    var selected: Tab = Tab.LIVE
        private set
    var expanded = false
        private set
    /** Attached to the screen's edge as a full-height strip (Live TV); otherwise the floating window. */
    var attached = true
        private set

    /** The rail's width collapsed: what the pages beside it leave free ([WIDTH_DP]). */
    val collapsedWidth = t.dp(WIDTH_DP)
    private val expandedWidth = t.dp(EXPANDED_DP)
    /** The hairline down the attached strip's right edge, separating it from the page. */
    private val rule = Paint()
    private val ruleWidth = t.dpf(1f)
    private var ruleAlpha = 0
    private val surface = Surface()
    private var mover: ValueAnimator? = null
    private var moveFrom = 0f
    private var moveTo = 0f

    init {
        orientation = VERTICAL
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        brand = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brandMark = ImageView(context).apply { setImageResource(R.drawable.ic_brand) }
        brand.addView(brandMark, LayoutParams(t.dp(MARK_DP), t.dp(MARK_DP)))
        brandText = t.label(context, NAME_SP, t.bold).apply { text = "FirePlayer"; visibility = GONE }
        brand.addView(brandText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(brand, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(22) })

        for (tab in Tab.values()) {
            if (tab == Tab.SETTINGS) addView(View(context), LayoutParams(1, 0, 1f)) // Settings at the bottom
            val row = item(tab)
            items[tab] = row
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(6) })
        }
        background = surface
        placeContent()
        applyLook()
        setSelected(Tab.LIVE)
    }

    /**
     * The tabs' place inside the view, and the brand's size. Collapsed, the tabs (44 dp) fill the window or sit
     * centred in the strip, [SIDE_DP] from the view's edges either way, so nothing moves between the two. Widened as a
     * window, the tabs keep [WINDOW_PAD_DP] clear of its edges; widened, the mark and the name are larger.
     */
    private fun placeContent() {
        val side = t.dp(RailShape.sideDp(attached, expanded))
        setPadding(side, t.dp(22), side, t.dp(22))
        val mark = t.dp(if (expanded) MARK_EXPANDED_DP else MARK_DP)
        brandMark.layoutParams = brandMark.layoutParams.apply { width = mark; height = mark }
        // The mark stays centred over the tabs' 22 dp icons (11 dp into each tab).
        brand.setPadding(t.dp(22) - mark / 2, 0, 0, 0)
        brandText.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (expanded) NAME_EXPANDED_SP else NAME_SP)
        brandText.layoutParams = (brandText.layoutParams as LayoutParams).apply { leftMargin = t.dp(if (expanded) 12 else 10) }
    }

    private fun item(tab: Tab): LinearLayout {
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(t.accent, t.accent, t.text),
        )
        val label = t.label(context, 16f, t.semibold).apply {
            text = tab.label
            setTextColor(colors)
            isDuplicateParentStateEnabled = true
            visibility = GONE
        }
        labels += label
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = tab.label
            setPadding(t.dp(11), 0, t.dp(12), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(t.focusFill); cornerRadius = t.dpf(12f) })
                setEnterFadeDuration(Tokens.FADE_MS.toInt())
                setExitFadeDuration(Tokens.FADE_MS.toInt())
            }
            addView(ImageView(context).apply {
                setImageResource(tab.icon)
                imageTintList = colors
                isDuplicateParentStateEnabled = true
            }, LayoutParams(t.dp(22), t.dp(22)))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(14) })
            setOnClickListener { open(tab) }
            // The rail moves its own focus: the expanded rail is drawn over the page, so Android's nearest-view
            // search found the page (the guide) below a tab before the next tab, which sits further down.
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener isRailKey(keyCode)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { open(tab); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { step(tab, down = true, held = event.repeatCount > 0); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { step(tab, down = false, held = event.repeatCount > 0); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true // nothing to the left of the rail
                    else -> false
                }
            }
        }
    }

    private fun isRailKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT

    /** OK or Right on a tab: show its page and go into it. */
    private fun open(tab: Tab) {
        if (tab != selected) onTab?.invoke(tab)
        onEnterPage?.invoke()
    }

    /**
     * Up/Down: the previous or next tab. Down from the last tab goes round to the first and Up from the first to the
     * last; [held], it stops there instead (holding the D-pad doesn't go round and round).
     */
    private fun step(from: Tab, down: Boolean, held: Boolean) {
        val tabs = items.keys.toList()
        val to = ListMath.step(tabs.indexOf(from), tabs.size, down, held) { true }
        if (to >= 0) items[tabs[to]]?.requestFocus()
    }

    /**
     * Seen from the page, the rail is one target, the selected tab: Left from the page (or the guide's column) comes
     * back to the tab you came from, not to whichever tab happens to be nearest on screen.
     */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus()) return super.addFocusables(views, direction, focusableMode)
        items[selected]?.let { if (it.isShown && it.isFocusable) views.add(it) }
    }

    fun setSelected(tab: Tab) {
        selected = tab
        for ((k, v) in items) v.isSelected = k == tab
    }

    fun focusSelected() {
        items[selected]?.requestFocus()
    }

    /**
     * Wide with labels while focus is in the rail; icons only otherwise. The same window (or strip), only wider, drawn
     * over the page. Widened, its glass shows the page behind it, blurred (copied once as it opens, and again only if
     * the page changes meanwhile); narrow, there is only the still wallpaper behind it and nothing is copied.
     */
    fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        brandText.visibility = if (on) VISIBLE else GONE
        for (l in labels) l.visibility = if (on) VISIBLE else GONE
        placeContent()
        surface.showBackdrop(on)
        layoutParams = layoutParams.apply { width = if (on) expandedWidth else collapsedWidth }
    }

    /**
     * The strip attached to the screen's edge ([on], Live TV) or the floating window (every other tab). With
     * [animateMs] it moves from one to the other over that time: its drawing is stretched and faded each frame, and
     * nothing is laid out or made on the way.
     */
    fun setAttached(on: Boolean, animateMs: Long = 0L) {
        if (attached == on) return
        attached = on
        placeContent()
        mover?.cancel()
        mover = null
        val to = if (on) 1f else 0f
        if (animateMs <= 0L || !isLaidOut || !isShown) {
            surface.attach = to
            return
        }
        moveFrom = surface.attach
        moveTo = to
        mover = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animateMs
            interpolator = MOVE_EASE
            addUpdateListener { surface.attach = moveFrom + (moveTo - moveFrom) * it.animatedFraction }
            start()
        }
    }

    /**
     * The rail's surfaces in the look now on. Classic: the classic dark fill, solid (a rounded window with the classic
     * hairline, or the flat strip). Glass: the shared dark glass, which never lets the page's words show through the
     * labels: widened over the page it shows the page blurred under its tint.
     */
    fun applyLook() {
        if (Glass.on) {
            val window = Glass.overlayDrawable(context, Glass.Kind.POPUP)
            val strip = Glass.overlayDrawable(context, Glass.Kind.POPUP).apply { flat = true }
            // Before they're the rail's: narrow, nothing is copied for them.
            window.backdropOn = expanded
            strip.backdropOn = expanded
            surface.set(window, strip)
        } else {
            val window = GradientDrawable().apply {
                setColor(CLASSIC_FILL)
                cornerRadius = t.dpf(GlassStyle.RADIUS_POPUP)
                setStroke(t.dp(1), CLASSIC_RULE)
            }
            surface.set(window, ColorDrawable(CLASSIC_FILL))
        }
        rule.color = if (Glass.on) GLASS_RULE else CLASSIC_RULE
        ruleAlpha = rule.alpha
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The attached strip's right edge (the tabs keep clear of it), fading with the strip. Drawn only when the rail is.
        val f = surface.attach
        if (f <= 0f) return
        rule.alpha = (ruleAlpha * f).toInt()
        canvas.drawRect(width - ruleWidth, 0f, width.toFloat(), height.toFloat(), rule)
    }

    override fun onDetachedFromWindow() {
        mover?.cancel()
        mover = null
        surface.attach = if (attached) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * The rail's background: the floating window, drawn inset in the view, and the attached strip, filling it, with
     * [attach] between them (0 the window, 1 the strip). On the way the window is stretched towards the strip's box
     * and fades as the strip fades in: two drawings a frame, of things already made.
     */
    private inner class Surface : Drawable(), Drawable.Callback {
        private var window: Drawable? = null
        private var strip: Drawable? = null
        private val box = Rect()
        private val insetX = t.dp(RailShape.FLOAT_SIDE_DP)
        private val insetY = t.dp(RailShape.FLOAT_END_DP)

        var attach = 1f
            set(v) {
                val f = v.coerceIn(0f, 1f)
                if (field == f) return
                field = f
                invalidateSelf()
            }

        fun set(window: Drawable, strip: Drawable) {
            this.window?.callback = null
            this.strip?.callback = null
            this.window = window
            this.strip = strip
            window.callback = this
            strip.callback = this
            place(bounds)
            invalidateSelf()
        }

        /** Glass: whether the page behind shows through, blurred (widened over the page), or the tint alone. */
        fun showBackdrop(on: Boolean) {
            (window as? GlassDrawable)?.backdropOn = on
            (strip as? GlassDrawable)?.backdropOn = on
        }

        private fun place(b: Rect) {
            if (b.isEmpty) return
            box.set(b.left + insetX, b.top + insetY, b.right - insetX, b.bottom - insetY)
            window?.bounds = box
            strip?.bounds = b
        }

        override fun onBoundsChange(bounds: Rect) = place(bounds)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || box.isEmpty) return
            val f = attach
            val w = window
            if (w != null && f < 1f) {
                w.alpha = (255 * (1f - f)).toInt()
                if (f > 0f) {
                    // The window's box on its way to the strip's.
                    val left = RailShape.between(box.left.toFloat(), b.left.toFloat(), f)
                    val top = RailShape.between(box.top.toFloat(), b.top.toFloat(), f)
                    val right = RailShape.between(box.right.toFloat(), b.right.toFloat(), f)
                    val bottom = RailShape.between(box.bottom.toFloat(), b.bottom.toFloat(), f)
                    val count = canvas.save()
                    canvas.translate(left, top)
                    canvas.scale((right - left) / box.width(), (bottom - top) / box.height())
                    canvas.translate(-box.left.toFloat(), -box.top.toFloat())
                    w.draw(canvas)
                    canvas.restoreToCount(count)
                } else {
                    w.draw(canvas)
                }
            }
            val s = strip
            if (s != null && f > 0f) {
                s.alpha = (255 * f).toInt()
                s.draw(canvas)
            }
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getPadding(padding: Rect): Boolean {
            padding.setEmpty()
            return false
        }

        override fun invalidateDrawable(who: Drawable) = invalidateSelf()

        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) = scheduleSelf(what, time)

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    companion object {
        /**
         * The collapsed rail's width (dp): its 44 dp tabs with [SIDE_DP] each side. The pages beside it start here (Live
         * TV's groups column, the other tabs' pages).
         */
        const val WIDTH_DP = 58
        /** Widened, with labels. */
        const val EXPANDED_DP = 212
        const val SIDE_DP = 7
        /** Widened as a floating window: the tabs keep this much clear of the window's edges. */
        const val WINDOW_PAD_DP = 6
        /** The brand mark and name, collapsed and widened (the mark's box; the name shows only widened). */
        private const val MARK_DP = 30
        private const val MARK_EXPANDED_DP = 38
        private const val NAME_SP = 21f
        private const val NAME_EXPANDED_SP = 24f
        /** Moving between the attached strip and the floating window, ms ([setAttached]). */
        const val ATTACH_MS = 240L

        /** Classic: a little darker than the classic wallpaper along the screen's left edge (about #0C1117). */
        private const val CLASSIC_FILL = 0xFF080B0F.toInt()
        /** The same hairlines as at the groups column's edge, one per look. */
        private const val CLASSIC_RULE = 0x14FFFFFF
        private const val GLASS_RULE = 0x1FFFFFFF
        private val MOVE_EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/**
 * The rail's shapes, in dp: where its floating window lies inside its view and how far the tabs are from the view's
 * edges in each state. Pure numbers, so they can be checked without a screen.
 */
object RailShape {
    /** The floating window's distance from the view's left and right edges (the screen's left edge is the view's). */
    const val FLOAT_SIDE_DP = NavRail.SIDE_DP
    /** And from its top and bottom. */
    const val FLOAT_END_DP = 8

    /**
     * The tabs' distance from the view's left and right edges. Collapsed it is the same attached or floating (the
     * window is exactly as wide as the tabs), so the icons don't move between Live TV and the other tabs; widened as a
     * window the tabs step in from its edges.
     */
    fun sideDp(attached: Boolean, expanded: Boolean): Int =
        if (expanded && !attached) FLOAT_SIDE_DP + NavRail.WINDOW_PAD_DP else NavRail.SIDE_DP

    /** The floating window's width, dp, in a view [viewDp] wide. */
    fun windowWidthDp(viewDp: Int): Int = viewDp - 2 * FLOAT_SIDE_DP

    /** [from] to [to], [f] of the way (0 at [from], 1 at [to]). */
    fun between(from: Float, to: Float, f: Float): Float = from + (to - from) * f
}
