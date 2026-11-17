package app.fireplayer.live.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fireplayer.R
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.GuideScope
import app.fireplayer.live.Playlist
import app.fireplayer.shell.Tokens

/**
 * The playlist and group column (design-layout): "All playlists", then each playlist, which expands to Favorites,
 * All channels and its own groups. Playlist scope stays visible: a group is always shown under its playlist.
 * Moving over an entry shows its channels after a short pause; Center or Right goes into the grid.
 */
class GuideSidebar(context: Context, private val t: Tokens) : RecyclerView(context) {

    interface Callbacks {
        /** [scope] should be shown; [enter] when the viewer pressed Center/Right to go into the grid. */
        fun onScope(scope: GuideScope, enter: Boolean)
    }

    sealed class Item {
        object All : Item()
        data class Head(val playlist: Playlist, val expanded: Boolean) : Item()
        data class Entry(val scope: GuideScope, val label: String) : Item()
    }

    var callbacks: Callbacks? = null
    private var items: List<Item> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var groups: Map<String, List<ChannelGroup>> = emptyMap()
    private val expanded = HashSet<String>()
    var current: GuideScope = GuideScope.AllPlaylists
        private set
    private val main = Handler(Looper.getMainLooper())
    private var pendingScope: GuideScope? = null
    private val showPending = Runnable { pendingScope?.let { if (it != current) pick(it, enter = false) } }

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = Adapter()
        itemAnimator = null
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipToPadding = false
        setPadding(t.dp(8), t.dp(10), t.dp(8), t.dp(10))
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setData(playlists: List<Playlist>, groups: Map<String, List<ChannelGroup>>, scope: GuideScope) {
        this.playlists = playlists
        this.groups = groups
        current = scope
        // The playlist holding the current scope starts open.
        playlistOf(scope)?.let { expanded += it }
        if (expanded.isEmpty() && playlists.size == 1) expanded += playlists[0].id
        rebuild()
    }

    /** Focus on the current scope's entry (coming back from the grid). */
    fun focusCurrent() {
        var i = items.indexOfFirst { scopeOf(it) == current }
        if (i < 0) {
            // Its playlist was folded: open it, so focus lands on the current entry (not "All playlists", whose
            // preview would switch the scope).
            playlistOf(current)?.let { if (expanded.add(it)) rebuild() }
            i = items.indexOfFirst { scopeOf(it) == current }.coerceAtLeast(0)
        }
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, height / 3)
        post { findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: getChildAt(0)?.requestFocus() }
    }

    private fun rebuild() {
        items = buildList {
            add(Item.All)
            for (p in playlists) {
                val open = p.id in expanded
                add(Item.Head(p, open))
                if (open) {
                    add(Item.Entry(GuideScope.Favorites(p.id), "Favorites"))
                    add(Item.Entry(GuideScope.AllChannels(p.id), "All channels"))
                    for (g in groups[p.id].orEmpty()) add(Item.Entry(GuideScope.Group(p.id, g.id), g.name))
                }
            }
        }
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
    }

    private fun scopeOf(item: Item): GuideScope? = when (item) {
        Item.All -> GuideScope.AllPlaylists
        is Item.Entry -> item.scope
        is Item.Head -> null
    }

    private fun playlistOf(scope: GuideScope): String? = when (scope) {
        GuideScope.AllPlaylists -> null
        is GuideScope.AllChannels -> scope.playlistId
        is GuideScope.Favorites -> scope.playlistId
        is GuideScope.Group -> scope.playlistId
    }

    private fun pick(scope: GuideScope, enter: Boolean) {
        main.removeCallbacks(showPending)
        current = scope
        adapter?.notifyItemRangeChanged(0, items.size)
        callbacks?.onScope(scope, enter)
    }

    private fun toggle(head: Item.Head) {
        if (!expanded.remove(head.playlist.id)) expanded += head.playlist.id
        val pos = items.indexOf(head)
        rebuild()
        post { findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(showPending)
        super.onDetachedFromWindow()
    }

    private inner class Holder(val row: LinearLayout, val label: TextView, val chevron: ImageView) : ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val colors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(t.accent, t.accent, t.text),
            )
            val label = t.label(ctx, 15f).apply {
                setTextColor(colors)
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = colors
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(t.dp(18), t.dp(18))
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = t.dp(36)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(t.focusFill); cornerRadius = t.dpf(9f) })
                    setEnterFadeDuration(Tokens.FADE_MS.toInt())
                    setExitFadeDuration(Tokens.FADE_MS.toInt())
                }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = t.dp(2) }
                addView(label)
                addView(chevron)
            }
            return Holder(row, label, chevron)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            val indent = if (item is Item.Entry) t.dp(26) else t.dp(14)
            h.row.setPadding(indent, 0, t.dp(12), 0)
            h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item is Item.Entry) 14.5f else 15.5f)
            h.label.typeface = if (item is Item.Entry) t.regular else t.semibold
            h.label.text = when (item) {
                Item.All -> "All playlists"
                is Item.Head -> item.playlist.name
                is Item.Entry -> item.label
            }
            h.chevron.visibility = if (item is Item.Head) View.VISIBLE else View.GONE
            if (item is Item.Head) h.chevron.rotation = if (item.expanded) 180f else 0f
            h.row.isSelected = scopeOf(item) == current
            h.row.contentDescription = when (item) {
                is Item.Head -> "${item.playlist.name}, playlist, ${if (item.expanded) "expanded" else "collapsed"}"
                else -> h.label.text
            }
            h.row.setOnClickListener {
                when (item) {
                    is Item.Head -> toggle(item)
                    else -> scopeOf(item)?.let { pick(it, enter = true) }
                }
            }
            h.row.setOnFocusChangeListener { _, has ->
                if (!has) return@setOnFocusChangeListener
                main.removeCallbacks(showPending)
                pendingScope = scopeOf(item)
                if (pendingScope != null) main.postDelayed(showPending, 350)
            }
            h.row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val s = scopeOf(item)
                        if (item is Item.Head && !item.expanded) { toggle(item); true }
                        else { pick(s ?: current, enter = true); true }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (item is Item.Head && item.expanded) { toggle(item); true } else false
                    }
                    else -> false
                }
            }
        }
    }
}
