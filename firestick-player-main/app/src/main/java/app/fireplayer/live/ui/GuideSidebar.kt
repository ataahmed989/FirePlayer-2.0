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
 * Moving over an entry shows its channels after a short pause; Center or Right goes into the grid. Center held on a
 * group opens its small window ([Callbacks.onHold]: Move to top, Hide category), whose edits show here at once
 * ([moveGroupToTop], [hideGroup]).
 */
class GuideSidebar(context: Context, private val t: Tokens) : RecyclerView(context) {

    interface Callbacks {
        /** [scope] should be shown; [enter] when the viewer pressed Center/Right to go into the grid. */
        fun onScope(scope: GuideScope, enter: Boolean)
        /** Center held on a group's [row] ([name] in full): its small window. Nothing is picked or previewed. */
        fun onHold(scope: GuideScope.Group, name: String, row: View)
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
    /** Set while focus is put on a row for the viewer (back from a small window): that row isn't previewed. */
    private var quietFocus = false

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = Adapter()
        itemAnimator = null
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipToPadding = false
        setPadding(t.dp(4), t.dp(6), t.dp(10), t.dp(10))
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setData(playlists: List<Playlist>, groups: Map<String, List<ChannelGroup>>, scope: GuideScope) {
        this.playlists = playlists
        this.groups = groups
        current = scope
        // The playlist holding the current scope starts open.
        playlistOf(scope)?.let { expanded += it }
        if (expanded.isEmpty() && playlists.size == 1) expanded += playlists[0].id
        // An update arriving while the viewer is in this column (a playlist or its guide finished loading) must not
        // move them: the row they're on is found again by its identity.
        val focusedKey = focusedChild?.let { getChildAdapterPosition(it) }?.let { items.getOrNull(it) }?.let { keyOf(it) }
        val old = items
        items = buildItems()
        if (items == old) adapter?.notifyItemRangeChanged(0, items.size) else {
            @Suppress("NotifyDataSetChanged")
            adapter?.notifyDataSetChanged()
        }
        if (focusedKey != null) post {
            if (hasFocus()) return@post
            val i = items.indexOfFirst { keyOf(it) == focusedKey }
            if (i >= 0) findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() else focusCurrent()
        }
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

    /**
     * Focus on [scope]'s row without previewing it: back from its small window, or onto the row that took a hidden
     * group's place. The current entry's if it isn't listed.
     */
    fun focusScope(scope: GuideScope) {
        val i = items.indexOfFirst { scopeOf(it) == scope }
        if (i < 0) { focusCurrent(); return }
        // Found even while a move or removal waits for the next layout (its position as it will be).
        val v = findViewHolderForAdapterPosition(i)?.itemView
        if (v != null) { focusQuietly(v); return }
        // Not on screen yet: a row moving up into a removed one's place shows with the next layout, where it is; one
        // further away is scrolled to.
        if (!hasPendingAdapterUpdates()) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, height / 3)
        post { findViewHolderForAdapterPosition(i)?.itemView?.let { focusQuietly(it) } ?: focusCurrent() }
    }

    private fun focusQuietly(v: View) {
        quietFocus = true
        v.requestFocus()
        quietFocus = false
    }

    /**
     * Move to top: [groupId] becomes the first of its playlist's groups, shown at once. Only its row moves (the rows it
     * passes step down one); when its new place is above the rows on screen, the list comes to it with the row staying
     * where it is on screen. False when it was first already, or isn't listed.
     */
    fun moveGroupToTop(playlistId: String, groupId: String): Boolean {
        val list = groups[playlistId] ?: return false
        val moved = GroupEdits.toTop(list, groupId) { it.id }
        if (moved === list) return false
        val key = entryKey(GuideScope.Group(playlistId, groupId))
        val lm = layoutManager as LinearLayoutManager
        val from = items.indexOfFirst { keyOf(it) == key }
        val firstShown = lm.findFirstVisibleItemPosition()
        val rowTop = findViewHolderForAdapterPosition(from)?.itemView?.top
        groups = groups + (playlistId to moved)
        items = buildItems()
        val to = items.indexOfFirst { keyOf(it) == key }
        if (from < 0 || to < 0) { rebuild(); return true }
        adapter?.notifyItemMoved(from, to)
        if (to < firstShown && rowTop != null) lm.scrollToPositionWithOffset(to, rowTop - paddingTop)
        return true
    }

    /**
     * Hide category: [groupId]'s row leaves the column at once. Returns the scope of the row that takes its place (the
     * next group, or the one above when it was the last: another group, or the playlist's All channels), for focus to
     * go to; when the guide was showing the hidden group, it shows that one now. Null when it isn't listed.
     */
    fun hideGroup(playlistId: String, groupId: String): GuideScope? {
        val list = groups[playlistId] ?: return null
        if (list.none { it.id == groupId }) return null
        val hidden = GuideScope.Group(playlistId, groupId)
        val landing = GroupEdits.afterHide(list.map { it.id }, groupId)?.let { GuideScope.Group(playlistId, it) }
            ?: GuideScope.AllChannels(playlistId)
        val pos = items.indexOfFirst { keyOf(it) == entryKey(hidden) }
        groups = groups + (playlistId to list.filter { it.id != groupId })
        items = buildItems()
        if (pos >= 0) adapter?.notifyItemRemoved(pos) else rebuild()
        if (current == hidden) pick(landing, enter = false)
        return landing
    }

    private fun rebuild() {
        items = buildItems()
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
    }

    private fun buildItems(): List<Item> =
        buildList {
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

    /** A row's identity across updates (RecyclerView keeps the focused row by it). */
    private fun keyOf(item: Item): String = when (item) {
        Item.All -> "all"
        is Item.Head -> "h|" + item.playlist.id
        is Item.Entry -> entryKey(item.scope)
    }

    private fun entryKey(scope: GuideScope) = "e|" + scope.encode()

    private val ids = HashMap<String, Long>()
    private fun idOf(item: Item): Long = ids.getOrPut(keyOf(item)) { ids.size + 1L }

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

    /**
     * Opens or folds a playlist. Only its rows come or go, so the playlist row keeps focus (the whole list rebuilt
     * dropped focus for a moment; it landed in the guide, which closed this column).
     */
    private fun toggle(head: Item.Head) {
        val id = head.playlist.id
        val pos = items.indexOfFirst { it is Item.Head && it.playlist.id == id }
        if (pos < 0) return
        val before = items.size
        if (!expanded.remove(id)) expanded += id
        items = buildItems()
        val delta = items.size - before
        adapter?.notifyItemChanged(pos)
        if (delta < 0) adapter?.notifyItemRangeRemoved(pos + 1, -delta)
        if (delta > 0) adapter?.notifyItemRangeInserted(pos + 1, delta)
        post { if (!hasFocus()) findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(showPending)
        super.onDetachedFromWindow()
    }

    private inner class Holder(val row: LinearLayout, val label: TextView, val chevron: ImageView) : ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        init { setHasStableIds(true) }

        override fun getItemCount() = items.size

        override fun getItemId(position: Int): Long = idOf(items[position])

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
            // A playlist's arrow sits before its name (the concepts): pointing right while folded, down while open.
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = colors
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(t.dp(18), t.dp(18)).apply { rightMargin = t.dp(8) }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = t.dp(34)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(t.focusFill); cornerRadius = t.dpf(10f) })
                    setEnterFadeDuration(Tokens.FADE_MS.toInt())
                    setExitFadeDuration(Tokens.FADE_MS.toInt())
                }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = t.dp(1) }
                addView(chevron)
                addView(label)
            }
            return Holder(row, label, chevron)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            // Playlist names a little larger than their groups (user); groups line up under the playlist's name.
            val indent = when (item) {
                Item.All -> t.dp(12)
                is Item.Head -> t.dp(6)
                is Item.Entry -> t.dp(32)
            }
            h.row.setPadding(indent, 0, t.dp(10), 0)
            h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item is Item.Entry) 14.5f else 16.5f)
            h.label.typeface = if (item is Item.Entry) t.regular else t.semibold
            h.label.text = when (item) {
                Item.All -> "All playlists"
                is Item.Head -> item.playlist.name
                is Item.Entry -> item.label
            }
            h.chevron.visibility = if (item is Item.Head) View.VISIBLE else View.GONE
            if (item is Item.Head) h.chevron.rotation = if (item.expanded) 0f else -90f
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
                if (pendingScope != null && !quietFocus) main.postDelayed(showPending, 350)
            }
            // Center held on a group: its small window, instead of picking it on release (the long press is used up)
            // or previewing it.
            val entry = item as? Item.Entry
            val group = entry?.scope as? GuideScope.Group
            if (entry != null && group != null) {
                h.row.setOnLongClickListener { row ->
                    main.removeCallbacks(showPending)
                    callbacks?.onHold(group, entry.label, row)
                    true
                }
            } else {
                h.row.setOnLongClickListener(null)
                h.row.isLongClickable = false
            }
            h.row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    // Center picks on release; held, a group's small window opens. Neither waits for the preview.
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        main.removeCallbacks(showPending)
                        false
                    }
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
