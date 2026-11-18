package app.fireplayer.live.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import app.fireplayer.R
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.PlaylistKind
import app.fireplayer.live.XtreamApi
import app.fireplayer.live.XtreamParser
import app.fireplayer.playback.PlayerHost
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.ListEntry
import app.fireplayer.ui.LongList
import app.fireplayer.ui.Rows
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.asSubRow
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live TV's settings windows, in the shared SidePanel:
 * - Live FirePlayer Settings ([openPlayerSettings], over Live TV: Menu or the Settings button): Playlists and EPG, then
 *   the shared Remote / Look / Defaults / Statuses / App sections ([SettingsPanels.renderPlayerLive]).
 * - Individual playlist settings (name, EPG sources, Xtream parameters or M3U link, account, Manage groups, updates).
 * - Manage groups (hide or show each group, all, new ones; reorder the shown ones; Assign favorites) and Reorder
 *   playlists: wide windows, long lists ([SidePanel.longList]) so 350 groups open at once.
 * - Assign favorites (a playlist's groups, then a group's channels: OK stars one), also opened from the guide.
 * - The Settings tab's Playlists, EPG and Live TV windows, built from the same pieces.
 * Saved passwords are never shown, nor an M3U link (it often carries the sign-in): only that one is saved.
 */
class LiveSettings(private val activity: Activity, private val panel: SidePanel) {

    private val db = LiveDb.get(activity)
    private val sync = LiveSync.get(activity)
    private val prefs = LivePrefs(activity)
    private val io = Executors.newSingleThreadExecutor()
    /** Favorites set in Assign favorites are stored here, never queued behind the settings' reads. */
    private val writes = Executors.newSingleThreadExecutor()
    private var released = false
    /** Questions to the provider (Active connections): never ahead of the settings' own reads and saves. */
    private val net = Executors.newSingleThreadExecutor()
    private val asking = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private var playlists: List<Playlist> = emptyList()
    private var epg: Map<String, LiveDb.EpgStatus> = emptyMap()
    /** Each playlist's groups (hidden ones too), read when its window opens. */
    private val groups = HashMap<String, List<ChannelGroup>>()
    /** "Active connections": the provider's count and allowance, asked when the playlist's window opens. */
    private val connections = HashMap<String, String>()

    /** The panel is told about every sync so statuses update while it's open. */
    private val listener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) {
            // A change this window made itself is already on screen (redrawing 350 groups would take a second).
            if (ownChanges > 0) { ownChanges--; return }
            if (panel.isOpen) reloadThen { panel.refresh() }
        }
    }

    /** Changes made here (group switches and order, playlist order) the guide is told about; this window isn't. */
    private var ownChanges = 0

    private fun changed(playlistId: String) {
        ownChanges++
        sync.changed(playlistId)
    }

    /**
     * The guide is told about changes made here a moment after the last one: ten groups switched or ten channels
     * starred in a row read the guide again once, not ten times (a read can take a second with a big playlist).
     */
    private val toTell = LinkedHashSet<String>()
    private val tell = Runnable {
        val ids = toTell.toList()
        toTell.clear()
        ids.forEach { changed(it) }
    }

    private fun tellGuide(playlistId: String) {
        toTell += playlistId
        main.removeCallbacks(tell)
        main.postDelayed(tell, TELL_MS)
    }

    init {
        sync.addListener(listener)
    }

    fun release() {
        sync.removeListener(listener)
        released = true
        // Changes still waiting to be told go now.
        main.removeCallbacks(tell)
        tell.run()
        io.shutdown()
        // Queued favorites are still stored.
        writes.shutdown()
        net.shutdownNow()
    }

    /** Reads the playlists and EPG statuses (and [groupsOf]'s groups) off the main thread, then [then]. */
    private fun reloadThen(groupsOf: String? = null, then: () -> Unit) {
        // The groups already shown are read again too (an update may have added a playlist's groups meanwhile).
        val ids = (groups.keys + listOfNotNull(groupsOf)).toSet()
        io.execute {
            val list = db.playlists()
            val statuses = db.epgStatuses().associateBy { it.source }
            val g = ids.associateWith { db.allGroups(it) }
            main.post {
                playlists = list
                epg = statuses
                groups.putAll(g)
                then()
            }
        }
    }

    // ---- Live FirePlayer Settings (the contextual window over Live TV) ---------------------------------------------

    /** Live FirePlayer Settings: Playlists and EPG, Remote, Look, Defaults, Statuses, App (user spec). */
    fun openPlayerSettings(shared: SettingsPanels, onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Settings", onClose) {
            header("Playlists and EPG")
            playlistRows(this)
            epgRows(this, expandable = true)
            shared.renderPlayerLive(this) { channelButtons(this) }
        }
    }

    /**
     * Remote › Channel changing buttons: the two ways as rows (the chosen one ticked), with what the chosen one does.
     * (As one row with its value, the long names didn't fit the window.)
     */
    private fun channelButtons(p: SidePanel) = with(p) {
        message("Channel changing buttons")
        val only = prefs.channelButtonsOnly
        row("D-pad + channel buttons", checked = !only) { prefs.channelButtonsOnly = false; refresh() }
        row("Channel buttons only", checked = only) { prefs.channelButtonsOnly = true; refresh() }
        message(
            if (only) "Channel +/− change channels. Up/Down or Center show the controls."
            else "With the controls hidden, Up/Down and Channel +/− change channels. Center shows the controls.",
        )
    }

    // ---- Playlists ------------------------------------------------------------------------------------------------

    /** The Settings tab's Playlists window. */
    fun openPlaylists(onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Playlists", onClose) { playlistRows(this) }
    }

    /** The playlists (each opens its settings), Reorder playlists under them, Add playlist, Update all playlists. */
    private fun playlistRows(p: SidePanel): Unit = with(p) {
        if (playlists.isEmpty()) {
            message("Add an Xtream Codes account or an M3U playlist link. Channels and the TV guide load from it.")
            // With nothing to put in order, what's wanted first is first.
            row("Add playlist") { addPlaylist() }
            reorderPlaylistsRow(this)
            return@with
        }
        for (pl in playlists) {
            row(pl.name, shortStatus(pl), twoLines = false) { openPlaylist(pl.id) }
        }
        reorderPlaylistsRow(this)
        row("Add playlist") { addPlaylist() }
        updateRow(this, "Update all playlists", playlists.maxOf { it.lastSync }) { sync.updateAll() }
    }

    /**
     * Reorder playlists: with fewer than two, dimmed and saying so; it stays focusable (focus doesn't jump past it)
     * and does nothing.
     */
    private fun reorderPlaylistsRow(p: SidePanel) {
        val ready = playlists.size > 1
        val row = p.row("Reorder playlists", if (ready) null else "Needs 2 playlists") {
            if (playlists.size > 1) p.openSub("Reorder playlists", wide = true) { reorderPlaylists(this) }
        }
        if (!ready) row.alpha = 0.45f
    }

    /**
     * An Update button: while anything is updating it's dimmed and does nothing (it stays focusable, so focus
     * doesn't jump), and says "Updating…"; otherwise it says when the last update was.
     */
    private fun updateRow(p: SidePanel, label: String, lastSync: Long, start: () -> Unit) {
        val busy = sync.busy
        val row = p.row(label, if (busy) "Updating…" else if (lastSync > 0) "Updated ${whenText(lastSync, short = true)}" else "Never updated") {
            if (!sync.busy) { start(); p.refresh() }
        }
        if (busy) row.alpha = 0.45f
    }

    /** "5:04 PM" today, "yesterday 5:04 PM", else "Nov 15, 5:04 PM" ([short]: "yesterday", "Nov 15"). */
    private fun whenText(t: Long, short: Boolean = false): String {
        val time = DateFormat.getTimeFormat(activity).format(Date(t))
        val day = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(day, then)) return time
        day.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, then)) return if (short) "yesterday" else "yesterday $time"
        val date = android.text.format.DateUtils.formatDateTime(activity, t, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        return if (short) date else "$date, $time"
    }

    private fun shortStatus(p: Playlist): String = when {
        p.lastStatus.isBlank() -> "Not updated yet"
        p.lastStatus.startsWith("Updated: ") -> p.lastStatus.removePrefix("Updated: ")
        else -> p.lastStatus
    }

    private fun openPlaylist(id: String) {
        val from = panel.session
        reloadThen(groupsOf = id) {
            // Only into the window it was asked from (it may have closed, or moved on, while the playlist was read).
            if (!panel.isOpen || panel.session != from) return@reloadThen
            val pl = playlists.firstOrNull { it.id == id } ?: return@reloadThen
            panel.openSub(pl.name, wide = true) { renderPlaylist(this, id) }
            askConnections(pl, panel.session)
        }
    }

    /** Asks the provider how many of the account's connections are in use (Xtream only), then shows it. */
    private fun askConnections(pl: Playlist, window: Int) {
        if (pl.kind != PlaylistKind.XTREAM || !asking.add(pl.id)) return
        connections[pl.id] = "Checking…"
        net.execute {
            val account = runCatching { XtreamApi(pl).get(null)?.let { XtreamParser.account(it) } }.getOrNull()
            val text = when {
                account == null -> "Couldn't ask the provider"
                account.maxConnections > 0 -> "${account.activeConnections} of ${account.maxConnections}"
                else -> "${account.activeConnections} in use"
            }
            main.post {
                asking -= pl.id
                connections[pl.id] = text
                // Only the playlist's own window shows it.
                if (panel.isOpen && panel.session == window) panel.refresh()
            }
        }
    }

    private fun renderPlaylist(p: SidePanel, id: String) = with(p) {
        val pl = playlists.firstOrNull { it.id == id } ?: run { message("This playlist was deleted."); return@with }
        message(if (sync.busy) "Updating…" else "${pl.lastStatus.ifBlank { "Not updated yet" }} (last update: ${if (pl.lastSync > 0) whenText(pl.lastSync) else "never"})")

        header("Playlist")
        row("Name", pl.name) { edit("Name", pl.name) { v -> save(pl.copy(name = v.ifBlank { pl.name })) } }

        // This playlist's own EPG comes first; others fill in only by an exact EPG id (automatic or manual fallback is
        // still to be decided: the automatic one stays provisional).
        header("EPG sources")
        val own = epg[pl.id]
        // A link of the viewer's own can carry a sign-in too: only where it points is shown, and its box starts empty.
        row("Own EPG", if (pl.epgUrl.isBlank()) (if (pl.kind == PlaylistKind.XTREAM) "The server's own" else "The playlist's own") else hostName(pl.epgUrl)) {
            SidePanel.prompt(activity, "EPG link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                hint = if (pl.epgUrl.isBlank()) "An XMLTV link (blank: the playlist's own)" else "Saved · type a new one to replace it") { v ->
                if (v.isNotBlank()) save(pl.copy(epgUrl = normalized(v)))
            }
        }
        if (pl.epgUrl.isNotBlank()) row("Use the playlist's own EPG") { save(pl.copy(epgUrl = "")) }.asSubRow()
        row("Own EPG status", epgValue(own)) {}.asSubRow()
        val others = playlists.filter { it.id != pl.id && (epg[it.id]?.programmes ?: 0) > 0 }
        row("Fallback", if (others.isEmpty()) "None" else others.joinToString { it.name }, enabled = false) {}
        message("Only when this playlist has no listings of its own: other playlists' EPGs, for channels with exactly the same EPG id.")

        if (pl.kind == PlaylistKind.XTREAM) {
            header("Xtream Codes parameters")
            row("Server", pl.url) {
                edit("Server address", pl.url, uri = true) { v ->
                    val url = normalized(v)
                    // A name made from the old address follows the new one; a name the viewer chose stays.
                    val name = if (pl.name == hostName(pl.url)) hostName(url) else pl.name
                    save(pl.copy(url = url, name = name))
                }
            }
            row("Username", pl.username) { edit("Username", pl.username) { v -> save(pl.copy(username = v.trim())) } }
            row("Password", if (pl.password.isNotEmpty()) "Set" else "Not set") {
                SidePanel.prompt(activity, "Password", "", password = true) { v -> if (v.isNotEmpty()) save(pl.copy(password = v)) }
            }
            choice("Output format", listOf("MPEG-TS (.ts)", "HLS (.m3u8)"), if (pl.output == "m3u8") 1 else 0) { i ->
                save(pl.copy(output = if (i == 1) "m3u8" else "ts"))
            }
            row("Include TV channels", if (pl.includeLive) "On" else "Off") { save(pl.copy(includeLive = !pl.includeLive)) }
            includeVod(this)

            // Read-only, as the provider reports them.
            header("Account")
            row("Account expires", if (pl.expiresAt > 0) DateFormat.getMediumDateFormat(activity).format(Date(pl.expiresAt)) else "Not reported") {}
            row("Connection allowance", if (pl.maxConnections > 0) "${pl.maxConnections}" else "Not reported") {}
            row("Active connections", connections[pl.id] ?: "—") { askConnections(pl, session); refresh() }
        } else {
            header("M3U playlist")
            // The link often carries the sign-in: only where it points is shown, and its box starts empty.
            row("Playlist link", hostName(pl.url)) {
                SidePanel.prompt(activity, "Playlist link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                    hint = "Saved · type a new one to replace it") { v ->
                    if (v.isBlank()) return@prompt
                    val url = normalized(v)
                    val name = if (pl.name == hostName(pl.url)) hostName(url) else pl.name
                    save(pl.copy(url = url, name = name))
                }
            }
            row("Include TV channels", if (pl.includeLive) "On" else "Off") { save(pl.copy(includeLive = !pl.includeLive)) }
            includeVod(this)
        }

        header("Groups")
        val g = groups[pl.id].orEmpty()
        row("Manage groups", if (g.isEmpty()) "No groups" else "${g.count { !it.hidden }} of ${g.size} shown") {
            openSub("Manage groups", wide = true) { manageGroups(this, pl.id) }
        }

        header("Updates")
        val hours = listOf(6, 12, 24, 48)
        choice("Update interval", hours.map { "Every $it hours" }, hours.indexOf(pl.updateHours).let { if (it < 0) 2 else it }) { i ->
            save(pl.copy(updateHours = hours[i]))
        }
        updateRow(this, "Update playlist", pl.lastSync) { sync.update(pl.id) }
        row("Delete playlist") {
            FireDialog.ask(activity, "Delete \"${pl.name}\"? Its channels, favorites and guide go too.", listOf(
                "Delete" to {
                    val window = session
                    // Its channel stops if it's the one playing (or remembered), and isn't brought back later.
                    PlayerHost.peek()?.let { h -> if (ChannelKey.parse(h.currentKey)?.playlistId == pl.id) h.stop() }
                    if (prefs.lastChannel?.playlistId == pl.id) prefs.lastChannel = null
                    io.execute {
                        db.deletePlaylist(pl.id)
                        val list = db.playlists()
                        main.post {
                            playlists = list
                            sync.changed(pl.id)
                            // Back to the list it was opened from (it no longer lists this one), if still on this window.
                            if (panel.isOpen && panel.session == window) { if (panel.hasParent) panel.back() else panel.close() }
                            else if (panel.isOpen) panel.refresh()
                        }
                    }
                },
                "Cancel" to {},
            ))
        }
    }

    /** "Include VOD": shown, off, and can't be turned on (not a feature yet: films and shows come from add-ons). */
    private fun includeVod(p: SidePanel) = with(p) {
        row("Include VOD", "Off", enabled = false) {}
        message("Films and series from playlists aren't available yet.")
    }

    private fun edit(title: String, initial: String, uri: Boolean = false, onDone: (String) -> Unit) {
        val type = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
        SidePanel.prompt(activity, title, initial, inputType = type) { onDone(it) }
    }

    /** Saves a change, then updates the playlist when what it loads from changed. */
    private fun save(p: Playlist) {
        val before = playlists.firstOrNull { it.id == p.id }
        io.execute {
            db.savePlaylist(p)
            val list = db.playlists()
            main.post {
                playlists = list
                // The update starts first, so the window redraws with its Update buttons already dimmed.
                if (before == null || before.url != p.url || before.username != p.username || before.password != p.password ||
                    before.output != p.output || before.includeLive != p.includeLive || before.epgUrl != p.epgUrl
                ) sync.update(p.id) else if (before.name != p.name) changed(p.id)
                panel.refresh()
            }
        }
    }

    private fun addPlaylist() {
        FireDialog.ask(activity, "Add playlist", listOf(
            "Xtream Codes" to { addXtream() },
            "M3U playlist link" to { addM3u() },
        ))
    }

    private fun addXtream() {
        edit("Server address (for example http://example.com:8080)", "", uri = true) { server ->
            if (server.isBlank()) return@edit
            edit("Username", "") { user ->
                SidePanel.prompt(activity, "Password", "", password = true) { pass ->
                    val url = normalized(server)
                    create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.XTREAM, url, user.trim(), pass, position = playlists.size))
                }
            }
        }
    }

    private fun addM3u() {
        edit("Playlist link", "", uri = true) { link ->
            if (link.isBlank()) return@edit
            val url = normalized(link)
            create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.M3U, url, position = playlists.size))
        }
    }

    private fun create(p: Playlist) {
        io.execute {
            db.savePlaylist(p)
            main.post {
                sync.update(p.id)
                openPlaylist(p.id)
            }
        }
    }

    private fun normalized(s: String): String {
        val v = s.trim()
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(v)) v else "http://$v"
    }

    private fun hostName(url: String): String = Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Playlist"

    // ---- Reorder (playlists and groups) ---------------------------------------------------------------------------

    /**
     * A list the viewer puts in order ([Reorder]): OK picks an entry up (it says "Moving"), Up/Down carry it, OK puts
     * it down; the button beside each moves it to the top. Every move is saved as it happens ([save]). A long list:
     * only the rows on screen exist, and a move only shows the rows it changed again, so it's instant with 350 groups.
     */
    private fun <T> reorderRows(p: SidePanel, r: Reorder<T>, name: (T) -> String, key: (T) -> String, save: (List<T>) -> Unit) {
        p.longList(object : LongList {
            override val size: Int get() = r.items.size + 1

            override fun entry(index: Int): ListEntry =
                if (index == 0) ListEntry("note", if (r.items.isEmpty()) "Nothing to put in order: every group is hidden. Show some in Manage groups first." else REORDER_NOTE, kind = ListEntry.Kind.NOTE)
                else r.items[index - 1].let { item ->
                    ListEntry("i|" + key(item), name(item), r.value(index - 1), button = R.drawable.ic_move_top, buttonLabel = "Move to top")
                }

            override fun onClick(index: Int) {
                val before = r.press(index - 1)
                if (before >= 0 && before != index - 1) p.changed(before + 1)
                p.changed(index)
            }

            override fun onButton(index: Int) {
                val i = index - 1
                val before = r.toTop(i)
                if (i > 0) save(r.items.toList())
                // Every row down to where it was now says another place; one picked up further down is put down.
                p.changed(1, i + 1)
                if (before > i) p.changed(before + 1)
                p.focusEntry(1)
            }

            override fun onKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
                val step = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    else -> return false
                }
                if (r.moving != index - 1) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val to = r.carry(step)
                    if (to >= 0) {
                        save(r.items.toList())
                        p.changed(index)
                        p.changed(to + 1)
                        // Focus follows the entry being moved (the list glides along with it).
                        p.focusEntry(to + 1)
                    }
                }
                return true
            }
        })
    }

    private fun reorderPlaylists(p: SidePanel) {
        val r = reorderState(p, "playlists") { Reorder(playlists) }
        reorderRows(p, r, { it.name }, { it.id }) { order ->
            // In memory at once (the windows behind this one show it), each with its new place for later saves (and as
            // last read, should an update have changed its status meanwhile).
            val now = playlists.associateBy { it.id }
            playlists = order.mapIndexed { i, pl -> (now[pl.id] ?: pl).copy(position = i) }
            playlistOrder.submit(order.map { it.id })
        }
    }

    /** Playlist order, stored as it changes (only the newest of quick moves). */
    private val playlistOrder = LatestSave<List<String>>(io) { ids ->
        db.setPlaylistOrder(ids)
        main.post { ids.firstOrNull()?.let { tellGuide(it) } }
    }

    /** Each playlist's group order, stored as it changes (only the newest of quick moves). */
    private val groupOrders = HashMap<String, LatestSave<List<String>>>()

    private fun reorderGroups(p: SidePanel, playlistId: String) {
        val r = reorderState(p, "groups|$playlistId") {
            // Hidden groups aren't listed (user): only the ones the guide shows are put in order.
            Reorder(groups[playlistId].orEmpty().filter { !it.hidden })
        }
        reorderRows(p, r, { it.name }, { it.id }) { order ->
            // The groups in memory take the new order now (hidden ones after); the database follows.
            val ids = order.map { it.id }
            val all = groups[playlistId].orEmpty()
            val listed = ids.toHashSet()
            groups[playlistId] = order + all.filter { it.id !in listed }
            groupOrders.getOrPut(playlistId) {
                LatestSave(io) { saved ->
                    db.setGroupOrder(playlistId, saved)
                    main.post { tellGuide(playlistId) }
                }
            }.submit(ids)
        }
    }

    // A reorder list lives while its window is open (keyed by the window's session), so refreshes keep the order.
    private var reorderKey: String? = null
    private var reorderSession = -1
    private var reorder: Reorder<*>? = null

    @Suppress("UNCHECKED_CAST")
    private fun <T> reorderState(p: SidePanel, key: String, make: () -> Reorder<T>): Reorder<T> {
        if (reorderKey != key || reorderSession != p.session || reorder == null) {
            reorderKey = key
            reorderSession = p.session
            reorder = make()
        }
        return reorder as Reorder<T>
    }

    // ---- Manage groups ----------------------------------------------------------------------------------------------

    /**
     * Manage groups on its own, over the guide: [playlistId]'s window, with focus on [groupId]'s row if given. The
     * playlist and its groups are read first. [onClose] runs when it closes.
     */
    fun openManageGroups(playlistId: String, groupId: String? = null, onClose: (() -> Unit)? = null) = reloadThen(groupsOf = playlistId) {
        panel.open("Manage groups", onClose, wide = true) { manageGroups(this, playlistId) }
        if (groupId != null) panel.focusEntry(GROUP + groupId)
    }

    /**
     * Manage groups (user spec), one wide window per playlist: Assign favorites; Reorder groups (how many are shown);
     * then Hide or show — Hide new groups, Show all, Hide all, and a switch for each group. Hidden groups and their
     * channels leave the guide. A long list (350 groups open at once); a switch flips in place at once and is stored
     * behind it.
     */
    private fun manageGroups(p: SidePanel, playlistId: String): Unit = with(p) {
        if (playlists.none { it.id == playlistId }) { message("This playlist was deleted."); return@with }
        if (groups[playlistId].isNullOrEmpty()) { message("This playlist has no groups yet. They appear after its next update."); return@with }
        longList(GroupSwitches(p, playlistId))
    }

    private inner class GroupSwitches(private val p: SidePanel, private val playlistId: String) : LongList {
        private val all: List<ChannelGroup> get() = groups[playlistId].orEmpty()

        override val size: Int get() = FIRST_GROUP + all.size

        override fun entry(index: Int): ListEntry = when (index) {
            0 -> ListEntry("favorites", "Assign favorites")
            1 -> ListEntry("reorder", "Reorder groups", "${all.count { !it.hidden }} shown")
            2 -> ListEntry("hideOrShow", "Hide or show", kind = ListEntry.Kind.HEADING)
            3 -> ListEntry("hideNew", "Hide new groups", if (playlists.firstOrNull { it.id == playlistId }?.hideNewGroups == true) "On" else "Off")
            4 -> ListEntry("showAll", "Show all")
            5 -> ListEntry("hideAll", "Hide all")
            else -> all[index - FIRST_GROUP].let { g -> ListEntry(GROUP + g.id, g.name, if (g.hidden) "Off" else "On", sub = true) }
        }

        override fun onClick(index: Int) {
            when (index) {
                0 -> openFavoriteGroups(playlistId)
                1 -> p.openSub("Reorder groups", wide = true) { reorderGroups(this, playlistId) }
                3 -> {
                    val pl = playlists.firstOrNull { it.id == playlistId } ?: return
                    val updated = pl.copy(hideNewGroups = !pl.hideNewGroups)
                    // Shown at once; stored behind it.
                    playlists = playlists.map { if (it.id == pl.id) updated else it }
                    p.changed(index)
                    io.execute { db.savePlaylist(updated) }
                }
                4 -> setAllGroups(p, playlistId, hidden = false)
                5 -> setAllGroups(p, playlistId, hidden = true)
                else -> {
                    // The group as it is now (this row may have been flipped since it was drawn).
                    val g = all.getOrNull(index - FIRST_GROUP) ?: return
                    val hide = !g.hidden
                    groups[playlistId] = all.map { if (it.id == g.id) it.copy(hidden = hide) else it }
                    p.changed(index)
                    p.changed(1)
                    io.execute {
                        db.setGroupHidden(playlistId, g.id, hide)
                        main.post { tellGuide(playlistId) }
                    }
                }
            }
        }
    }

    /** Show all / Hide all: shown at once in place, stored behind (switches pressed after it are stored after it too). */
    private fun setAllGroups(p: SidePanel, playlistId: String, hidden: Boolean) {
        val all = groups[playlistId].orEmpty()
        groups[playlistId] = all.map { it.copy(hidden = hidden) }
        p.changed(1)
        p.changed(FIRST_GROUP, all.size)
        io.execute {
            db.setAllGroupsHidden(playlistId, hidden)
            main.post { tellGuide(playlistId) }
        }
    }

    // ---- Assign favorites -------------------------------------------------------------------------------------------

    /**
     * Assign favorites on its own, over the guide (user spec). With [groupId] it opens on that group's channels, with
     * focus on [channel] if given, and Back goes to the playlist's groups (on that group); without, on the groups.
     * [onClose] runs when it closes.
     */
    fun openAssignFavorites(playlistId: String, groupId: String? = null, channel: ChannelKey? = null, onClose: (() -> Unit)? = null) {
        reloadThen(groupsOf = playlistId) {
            readFavorites(playlistId, groupId?.takeIf { id -> groups[playlistId].orEmpty().any { it.id == id } }) {
                panel.open("Assign favorites", onClose, wide = true) { favoriteGroups(this, playlistId) }
                val group = groupId?.let { id -> groups[playlistId].orEmpty().firstOrNull { it.id == id } }
                if (group == null || shownChannels?.groupId != group.id) return@readFavorites
                // The groups underneath, on this group: where Back comes to.
                panel.focusEntry(GROUP + group.id)
                panel.openSub(group.name, wide = true) { favoriteChannels(this, playlistId, group.id) }
                if (channel != null) panel.focusEntry(channel.toString())
            }
        }
    }

    /** Each playlist's favorites per group, read as Assign favorites opens and kept up to date as stars change. */
    private val favoriteCounts = HashMap<String, MutableMap<String, Int>>()

    /** The group whose channels Assign favorites lists, as read when it opened (stars change in it as they're set). */
    private var shownChannels: GroupChannels? = null

    /**
     * Reads [playlistId]'s favorites per group (and [groupId]'s channels), once every star set before is stored, then
     * [then] on the main thread.
     */
    private fun readFavorites(playlistId: String, groupId: String?, then: () -> Unit) = afterWrites {
        io.execute {
            val counts = db.favoriteCounts(playlistId)
            val channels = groupId?.let { db.channels(GuideScope.Group(playlistId, it)) }
            main.post {
                favoriteCounts[playlistId] = counts.toMutableMap()
                if (groupId != null && channels != null) shownChannels = GroupChannels(playlistId, groupId, channels.toMutableList())
                then()
            }
        }
    }

    /** Assign favorites from Manage groups: the playlist's groups, over it. */
    private fun openFavoriteGroups(playlistId: String) {
        val from = panel.session
        readFavorites(playlistId, null) {
            // Only into the window it was asked from (it may have closed, or moved on, while they were read).
            if (!panel.isOpen || panel.session != from) return@readFavorites
            panel.openSub("Assign favorites", wide = true) { favoriteGroups(this, playlistId) }
        }
    }

    /**
     * Assign favorites, the groups: the shown ones in the guide's order, then the hidden ones (dimmed, under their
     * own heading), each with how many favorites it holds. OK opens a group's channels.
     */
    private fun favoriteGroups(p: SidePanel, playlistId: String): Unit = with(p) {
        if (playlists.none { it.id == playlistId }) { message("This playlist was deleted."); return@with }
        val ordered = FavoriteGroups.order(groups[playlistId].orEmpty())
        if (ordered.isEmpty()) { message("This playlist has no groups yet. They appear after its next update."); return@with }
        val shown = ordered.count { !it.hidden }
        // The hidden groups follow under a heading of their own.
        val headingAt = if (shown < ordered.size) shown else -1
        longList(object : LongList {
            override val size: Int get() = ordered.size + if (headingAt >= 0) 1 else 0

            private fun groupAt(index: Int): ChannelGroup? =
                if (headingAt < 0 || index < headingAt) ordered[index] else if (index == headingAt) null else ordered[index - 1]

            override fun entry(index: Int): ListEntry {
                val g = groupAt(index) ?: return ListEntry("hidden", "Hidden groups", kind = ListEntry.Kind.HEADING)
                val count = favoriteCounts[playlistId]?.get(g.id) ?: 0
                return ListEntry(GROUP + g.id, g.name, FavoriteGroups.countText(count), faded = g.hidden)
            }

            override fun onClick(index: Int) {
                val g = groupAt(index) ?: return
                openFavoriteChannels(playlistId, g)
            }
        })
    }

    /** A group's channels in Assign favorites, read first (once every star set before is stored). */
    private fun openFavoriteChannels(playlistId: String, g: ChannelGroup) {
        val from = panel.session
        readFavorites(playlistId, g.id) {
            if (!panel.isOpen || panel.session != from) return@readFavorites
            panel.openSub(g.name, wide = true) { favoriteChannels(this, playlistId, g.id) }
        }
    }

    /**
     * Assign favorites, a group's channels: number and full name; a favorite is blue with a filled star. OK stars or
     * unstars one at once in place; it's stored behind it, and the guide is told (its rows, the details' star and the
     * Favorites list follow).
     */
    private fun favoriteChannels(p: SidePanel, playlistId: String, groupId: String): Unit = with(p) {
        val shown = shownChannels?.takeIf { it.playlistId == playlistId && it.groupId == groupId }
        if (shown == null || shown.channels.isEmpty()) { message("No channels in this group."); return@with }
        longList(shown)
    }

    private inner class GroupChannels(val playlistId: String, val groupId: String, val channels: MutableList<Channel>) : LongList {
        override val size: Int get() = channels.size

        override fun entry(index: Int): ListEntry = channels[index].let { c ->
            ListEntry(c.key.toString(), "${c.number}  ${c.name}", icon = if (c.favorite) R.drawable.ic_star_filled else R.drawable.ic_star, chosen = c.favorite)
        }

        override fun onClick(index: Int) {
            val c = channels.getOrNull(index) ?: return
            val fav = !c.favorite
            channels[index] = c.copy(favorite = fav)
            favoriteCounts.getOrPut(playlistId) { HashMap() }.let { FavoriteGroups.adjust(it, groupId, fav) }
            panel.changed(index)
            if (released) return
            writes.execute {
                runCatching { db.setFavorite(c.key, fav, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "Favorite not stored: ${it.javaClass.simpleName}") }
                main.post { tellGuide(playlistId) }
            }
        }
    }

    /** [action] on the main thread once every favorite set before is stored (a read after it then sees them all). */
    private fun afterWrites(action: () -> Unit) {
        if (released) return
        writes.execute { main.post { if (!released) action() } }
    }

    // ---- EPG ------------------------------------------------------------------------------------------------------

    /** The Settings tab's EPG window. */
    fun openEpg(onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("EPG", onClose) { epgRows(this, expandable = false) }
    }

    private fun epgValue(s: LiveDb.EpgStatus?): String = when {
        s == null -> "Not loaded"
        s.status == "OK" -> "${s.programmes} programmes · ${whenText(s.lastSync)}"
        else -> s.status
    }

    /**
     * EPG sources ([expandable]: behind an arrow, as in Live FirePlayer Settings), Past days to keep EPG, Update
     * interval, Update EPG, Clear EPG and the last status.
     */
    private fun epgRows(p: SidePanel, expandable: Boolean) = with(p) {
        if (!expandable) {
            message("Each playlist's guide comes from its own EPG. A playlist without one borrows another playlist's listings only for channels with the exact same EPG id.")
            header("EPG sources")
        }
        if (playlists.isEmpty()) { if (!expandable) message("No playlists yet."); return@with }
        val epgOpen = isPartOpen("epgSources")
        val loaded = playlists.count { (epg[it.id]?.programmes ?: 0) > 0 }
        val failing = playlists.any { epg[it.id]?.let { s -> s.status != "OK" } == true }
        if (expandable) {
            dropdownRow("EPG sources", "$loaded of ${playlists.size} loaded", if (failing) Rows.Badge.PROBLEM else if (loaded == playlists.size) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE, epgOpen) {
                togglePart("epgSources")
                refresh()
            }
        }
        if (!expandable || epgOpen) {
            for (pl in playlists) {
                val r = row(pl.name, epgValue(epg[pl.id])) { openPlaylist(pl.id) }
                if (expandable) r.asSubRow()
            }
        }
        if (!expandable) header("Storage")
        // Provisional: the outline fixes one day of past programmes; whether it's adjustable is still open.
        row("Past days to keep EPG", "1 day", enabled = false) {}
        val hours = listOf(6, 12, 24, 48)
        choice("Update interval", hours.map { "Every $it hours" }, hours.indexOf(prefs.epgUpdateHours).let { if (it < 0) 2 else it }) { i ->
            prefs.epgUpdateHours = hours[i]
        }
        // The last import that worked (a failed one also records its time).
        updateRow(this, "Update EPG", epg.values.filter { it.status == "OK" }.maxOfOrNull { it.lastSync } ?: 0L) { sync.updateEpg() }
        row("Clear EPG") {
            FireDialog.ask(activity, "Clear all programme listings? They load again at the next update.", listOf(
                "Clear" to { sync.clearEpg() },
                "Cancel" to {},
            ))
        }
        message("Last status: " + lastEpgStatus())
    }

    /** The most recent EPG result, in words. */
    private fun lastEpgStatus(): String {
        if (sync.busy) return "updating…"
        val last = epg.values.maxByOrNull { it.lastSync } ?: return "not loaded yet."
        val name = playlists.firstOrNull { it.id == last.source }?.name ?: "EPG"
        return if (last.status == "OK") "$name, ${last.programmes} programmes, ${whenText(last.lastSync)}."
        else "$name: ${last.status.removeSuffix(".")} (${whenText(last.lastSync)})."
    }

    // ---- Live TV (the Settings tab's window: options kept beside the contextual one) ---------------------------------

    fun openLiveOptions(onClose: (() -> Unit)? = null) {
        panel.open("Live TV", onClose) {
            header("Remote")
            channelButtons(this)
            header("Full screen")
            val hide = listOf(4, 6, 10, 0)
            choice("Hide controls after", listOf("4 seconds", "6 seconds", "10 seconds", "Never"), hide.indexOf(prefs.controlsHideSec).let { if (it < 0) 1 else it }) { i ->
                prefs.controlsHideSec = hide[i]
            }
            header("Guide")
            val secs = listOf(0, 30, 60, 120, 300)
            val labels = listOf("Never", "30 seconds", "1 minute", "2 minutes", "5 minutes")
            choice("Full screen after no key presses", labels, secs.indexOf(prefs.fullScreenAfterSec).let { if (it < 0) 1 else it }) { i ->
                prefs.fullScreenAfterSec = secs[i]
            }
        }
    }

    private companion object {
        const val TAG = "FirePlayerLive"
        /** How long after the last change here the guide is told about it. */
        const val TELL_MS = 400L
        const val REORDER_NOTE = "Press OK on one to pick it up, move it with Up and Down, and press OK again to put it down. " +
            "The button beside each moves it to the top."
        /** A group's entry key in Manage groups and Assign favorites (focus comes back to it by this). */
        const val GROUP = "g|"
        /** Manage groups: the group switches start after Assign favorites, Reorder groups, a heading and three rows. */
        const val FIRST_GROUP = 6
    }
}
