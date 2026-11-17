package app.fireplayer.shell

import android.content.Context
import android.content.res.ColorStateList
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

/**
 * The left tab rail: the one FirePlayer brand mark, then the tabs. It sits collapsed (icons only) while the page has
 * focus and widens with labels when focus comes into it, drawn over the page so the page (and live video) never
 * moves. Only tabs that work are shown; the others are added as they're built (user decision: no placeholders).
 */
class NavRail(context: Context, private val t: Tokens) : LinearLayout(context) {

    enum class Tab(val label: String, val icon: Int) {
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

    val collapsedWidth = t.dp(64)
    private val expandedWidth = t.dp(212)

    init {
        orientation = VERTICAL
        setPadding(t.dp(10), t.dp(14), t.dp(10), t.dp(14))
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        val brand = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(t.dp(6), 0, 0, 0)
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
            setOnClickListener { onTab?.invoke(tab) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (tab != selected) onTab?.invoke(tab)
                    onEnterPage?.invoke()
                    true
                } else {
                    false
                }
            }
        }
    }

    fun setSelected(tab: Tab) {
        selected = tab
        for ((k, v) in items) v.isSelected = k == tab
    }

    fun focusSelected() {
        items[selected]?.requestFocus()
    }

    /** Wide with labels while focus is in the rail; icons only otherwise. */
    fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        brandText.visibility = if (on) VISIBLE else GONE
        for (l in labels) l.visibility = if (on) VISIBLE else GONE
        layoutParams = layoutParams.apply { width = if (on) expandedWidth else collapsedWidth }
    }

    fun applyLook() {
        Glass.background(this, Glass.Kind.PANEL)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
