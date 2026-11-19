package app.fireplayer.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import app.fireplayer.ui.ListMath

/**
 * The left tab rail: the one FirePlayer brand mark, then the tabs. A slim flat strip along the screen's left edge, full
 * height and square, with a hairline down its right edge (user: a slim panel rather than a floating window, so no space
 * goes unused around it). Classic is a flat dark fill a little darker than the wallpaper; glass, a flat strip of the
 * glass. It sits collapsed (icons only) while the page has focus and widens with labels when focus comes into it, the
 * same strip drawn over the page so the page (and live video) never moves. Only tabs that work are shown; the others
 * are added as they're built (user decision: no placeholders).
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

    private val brandText: TextView
    private val items = LinkedHashMap<Tab, LinearLayout>()
    private val labels = ArrayList<TextView>()
    var selected: Tab = Tab.LIVE
        private set
    var expanded = false
        private set

    /** The strip's width collapsed: what the pages beside it leave free ([WIDTH_DP]). */
    val collapsedWidth = t.dp(WIDTH_DP)
    private val expandedWidth = t.dp(212)
    /** The hairline down the strip's right edge, separating it from the page. */
    private val rule = Paint()
    private val ruleWidth = t.dpf(1f)

    init {
        orientation = VERTICAL
        // The tabs (44 dp) centred in the collapsed strip, [SIDE_DP] each side; their icons stay put as it widens.
        setPadding(t.dp(SIDE_DP), t.dp(22), t.dp(SIDE_DP), t.dp(22))
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        val brand = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The 30 dp mark centred over the tabs' 22 dp icons.
            setPadding(t.dp(7), 0, 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brand.addView(ImageView(context).apply { setImageResource(R.drawable.ic_brand) }, LayoutParams(t.dp(30), t.dp(30)))
        brandText = t.label(context, 21f, t.bold).apply { text = "FirePlayer"; visibility = GONE }
        brand.addView(brandText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(10) })
        addView(brand, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(22) })

        for (tab in Tab.values()) {
            if (tab == Tab.SETTINGS) addView(View(context), LayoutParams(1, 0, 1f)) // Settings at the bottom
            val row = item(tab)
            items[tab] = row
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(6) })
        }
        applyLook()
        setSelected(Tab.LIVE)
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

    /** Wide with labels while focus is in the rail; icons only otherwise. The same strip, only wider. */
    fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        brandText.visibility = if (on) VISIBLE else GONE
        for (l in labels) l.visibility = if (on) VISIBLE else GONE
        layoutParams = layoutParams.apply { width = if (on) expandedWidth else collapsedWidth }
    }

    /**
     * The strip's surface, opaque in both looks: widened over the page, the groups column's names never show through
     * the labels. Glass: the glass's blurred wallpaper under its glaze, flat (no rim, bevel, shadow or corners, and
     * nothing new blurred). Classic: one flat colour.
     */
    fun applyLook() {
        val l = paddingLeft; val tp = paddingTop; val r = paddingRight; val b = paddingBottom
        background = if (Glass.on) {
            (Glass.drawable(context, Glass.Kind.PANEL) as GlassDrawable).apply { flat = true }
        } else {
            ColorDrawable(CLASSIC_FILL)
        }
        setPadding(l, tp, r, b)
        rule.color = if (Glass.on) GLASS_RULE else CLASSIC_RULE
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Over the strip's fill, on its right edge (the tabs keep clear of it). Drawn only when the rail is.
        canvas.drawRect(width - ruleWidth, 0f, width.toFloat(), height.toFloat(), rule)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        /**
         * The collapsed strip's width (dp): its 44 dp tabs with [SIDE_DP] each side. The pages beside it start here (Live
         * TV's groups column, the other tabs' pages).
         */
        const val WIDTH_DP = 58
        private const val SIDE_DP = 7

        /** Classic: a little darker than the classic wallpaper along the screen's left edge (about #0C1117). */
        private const val CLASSIC_FILL = 0xFF080B0F.toInt()
        /** The same hairlines as at the groups column's edge, one per look. */
        private const val CLASSIC_RULE = 0x14FFFFFF
        private const val GLASS_RULE = 0x1FFFFFFF
    }
}
