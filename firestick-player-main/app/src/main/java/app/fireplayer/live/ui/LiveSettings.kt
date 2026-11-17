package app.fireplayer.live.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.view.KeyEvent
import app.fireplayer.R
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.PlaylistKind
import app.fireplayer.live.XtreamApi
import app.fireplayer.live.XtreamParser
import app.fireplayer.playback.PlayerHost
import app.fireplayer.ui.FireDialog
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
 * - Manage groups (hide or show each group, all, new ones; reorder the shown ones) and Reorder playlists.
 * - The Settings tab's Playlists, EPG and Live TV windows, built from the same pieces.
 * Saved passwords are never shown, nor an M3U link (it often carries the sign-in): only that one is saved.
 */
class LiveSettings(private val activity: Activity, private val panel: SidePanel) {

    private val db = LiveDb.get(activity)
    private val sync = LiveSync.get(activity)
    private val prefs = LivePrefs(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var playlists: List<Playlist> = emptyList()
    private var epg: Map<String, LiveDb.EpgStatus> = emptyMap()
    /** Each playlist's groups (hidden ones too), read when its window opens. */
    private val groups = HashMap<String, List<ChannelGroup>>()
    /** "Active connections": the provider's count and allowance, asked when the playlist's window opens. */
    private val connections = HashMap<String, String>()

    // Expandable parts (closed each time their window opens).
    private var epgOpen = false
    private var epgSession = -1

    /** The panel is told about every sync so statuses update while it's open. */
    private val listener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) { if (panel.isOpen) reloadThen { panel.refresh() } }
    }

    init {
        sync.addListener(listener)
    }

    fun release() {
        sync.removeListener(listener)
        io.shutdown()
    }

    /** Reads the playlists and EPG statuses (and [groupsOf]'s groups) off the main thread, then [then]. */
    private fun reloadThen(groupsOf: String? = null, then: () -> Unit) {
        io.execute {
            val list = db.playlists()
            val statuses = db.epgStatuses().associateBy { it.source }
            val g = groupsOf?.let { db.allGroups(it) }
            main.post {
                playlists = list
                epg = statuses
                if (groupsOf != null && g != null) groups[groupsOf] = g
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

    /** Remote › Channel changing buttons, with what the chosen one does. */
    private fun channelButtons(p: SidePanel) = with(p) {
        choice("Channel changing buttons", listOf("D-pad + channel buttons", "Channel buttons only"), if (prefs.channelButtonsOnly) 1 else 0) { i ->
            prefs.channelButtonsOnly = i == 1
        }
        message(
            if (prefs.channelButtonsOnly) "Channel +/− change channels. Up/Down or Center show the controls."
            else "With the controls hidden, Up/Down and Channel +/− change channels. Center shows the controls.",
        )
    }

    // ---- Playlists ------------------------------------------------------------------------------------------------

    /** The Settings tab's Playlists window. */
    fun openPlaylists(onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Playlists", onClose) { playlistRows(this) }
    }

    /** The playlists (each opens its settings), Add playlist, Update all playlists, Reorder playlists. */
    private fun playlistRows(p: SidePanel) = with(p) {
        if (playlists.isEmpty()) message("Add an Xtream Codes account or an M3U playlist link. Channels and the TV guide load from it.")
        for (pl in playlists) {
            row(pl.name, shortStatus(pl), twoLines = false) { openPlaylist(pl.id) }
        }
        row("Add playlist") { addPlaylist() }
        if (playlists.isNotEmpty()) {
            updateRow(this, "Update all playlists", playlists.maxOf { it.lastSync }) { sync.updateAll() }
        }
        if (playlists.size > 1) row("Reorder playlists") { openSub("Reorder playlists") { reorderPlaylists(this) } }
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

    private fun openPlaylist(id: String) = reloadThen(groupsOf = id) {
        val pl = playlists.firstOrNull { it.id == id } ?: return@reloadThen
        panel.openSub(pl.name) { renderPlaylist(this, id) }
        askConnections(pl)
    }

    /** Asks the provider how many of the account's connections are in use (Xtream only), then shows it. */
    private fun askConnections(pl: Playlist) {
        if (pl.kind != PlaylistKind.XTREAM) return
        connections[pl.id] = "Checking…"
        io.execute {
            val account = runCatching { XtreamApi(pl).get(null)?.let { XtreamParser.account(it) } }.getOrNull()
            val text = when {
                account == null -> "Couldn't ask the provider"
                account.maxConnections > 0 -> "${account.activeConnections} of ${account.maxConnections}"
                else -> "${account.activeConnections} in use"
            }
            main.post {
                connections[pl.id] = text
                if (panel.isOpen) panel.refresh()
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
        row("Own EPG", pl.epgUrl.ifBlank { if (pl.kind == PlaylistKind.XTREAM) "The server's own" else "The playlist's own" }) {
            edit("EPG link (blank: the playlist's own)", pl.epgUrl, uri = true) { v -> save(pl.copy(epgUrl = v.trim())) }
        }
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
            row("Active connections", connections[pl.id] ?: "—") { askConnections(pl); refresh() }
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
            openSub("Manage groups") { manageGroups(this, pl.id) }
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
                    // Its channel stops if it's the one playing (or remembered), and isn't brought back later.
                    PlayerHost.peek()?.let { h -> if (ChannelKey.parse(h.currentKey)?.playlistId == pl.id) h.stop() }
                    if (prefs.lastChannel?.playlistId == pl.id) prefs.lastChannel = null
                    io.execute {
                        db.deletePlaylist(pl.id)
                        val list = db.playlists()
                        main.post {
                            playlists = list
                            sync.changed(pl.id)
                            // Back to the list it was opened from (it no longer lists this one).
                            if (panel.hasParent) panel.back() else panel.close()
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
                ) sync.update(p.id) else if (before.name != p.name) sync.changed(p.id)
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
     * A list the viewer puts in order: OK picks an entry up (it says "Moving"), Up/Down carry it, OK puts it down;
     * the button beside each moves it to the top. Every move is saved as it happens.
     */
    private class Reorder<T>(var items: MutableList<T>, val save: (List<T>) -> Unit) {
        var moving = -1
    }

    private fun <T> reorderRows(p: SidePanel, r: Reorder<T>, name: (T) -> String) = with(p) {
        message("Press OK on one to pick it up, move it with Up and Down, and press OK again to put it down. The button beside each moves it to the top.")
        r.items.forEachIndexed { i, item ->
            val moving = r.moving == i
            rowWithButton(
                name(item),
                if (moving) "Moving" else "${i + 1}",
                R.drawable.ic_move_top,
                "Move to top",
                onClick = { r.moving = if (moving) -1 else i; refresh() },
                onButton = {
                    if (i > 0) { r.items.add(0, r.items.removeAt(i)); r.moving = -1; r.save(r.items.toList()); refresh(); focusRow(0) }
                },
                keys = { code, ev ->
                    val step = when (code) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
                    if (!moving || step == 0) return@rowWithButton false
                    val to = i + step
                    if (ev.action == KeyEvent.ACTION_DOWN && to in r.items.indices) {
                        r.items.add(to, r.items.removeAt(i))
                        r.moving = to
                        r.save(r.items.toList())
                        // Focus follows the entry: it's found again by its name.
                        refresh()
                    }
                    true
                },
            )
        }
    }

    private fun reorderPlaylists(p: SidePanel) {
        val r = reorderState(p, "playlists") {
            Reorder(playlists.toMutableList()) { order ->
                io.execute {
                    db.setPlaylistOrder(order.map { it.id })
                    val list = db.playlists()
                    main.post { playlists = list; order.firstOrNull()?.let { sync.changed(it.id) } }
                }
            }
        }
        reorderRows(p, r) { it.name }
    }

    // ---- Manage groups ----------------------------------------------------------------------------------------------

    /**
     * Manage groups (user spec): Order — the shown groups in the viewer's order; Hide or show — "Hide new groups",
     * Show all, Hide all, and a switch for each group. Hidden groups and their channels leave the guide.
     */
    private fun manageGroups(p: SidePanel, playlistId: String) = with(p) {
        val pl = playlists.firstOrNull { it.id == playlistId } ?: run { message("This playlist was deleted."); return@with }
        val all = groups[playlistId].orEmpty()
        if (all.isEmpty()) { message("This playlist has no groups yet. They appear after its next update."); return@with }
        header("Order")
        row("Reorder groups", "${all.count { !it.hidden }} shown") {
            openSub("Reorder groups") { reorderGroups(this, playlistId) }
        }
        header("Hide or show")
        row("Hide new groups", if (pl.hideNewGroups) "On" else "Off") { save(pl.copy(hideNewGroups = !pl.hideNewGroups)) }
        row("Show all") { setGroups(playlistId) { db.setAllGroupsHidden(playlistId, false) } }
        row("Hide all") { setGroups(playlistId) { db.setAllGroupsHidden(playlistId, true) } }
        for (g in all) {
            row(g.name, if (g.hidden) "Off" else "On") { setGroups(playlistId) { db.setGroupHidden(playlistId, g.id, !g.hidden) } }.asSubRow()
        }
    }

    /** Applies a change to the groups, reads them again, and lets the guide follow. */
    private fun setGroups(playlistId: String, change: () -> Unit) {
        io.execute {
            change()
            val g = db.allGroups(playlistId)
            main.post {
                groups[playlistId] = g
                if (panel.isOpen) panel.refresh()
                sync.changed(playlistId)
            }
        }
    }

    private fun reorderGroups(p: SidePanel, playlistId: String) {
        val r = reorderState(p, "groups|$playlistId") {
            // Hidden groups aren't listed (user): only the ones the guide shows are put in order.
            Reorder(groups[playlistId].orEmpty().filter { !it.hidden }.toMutableList()) { order ->
                io.execute {
                    db.setGroupOrder(playlistId, order.map { it.id })
                    val g = db.allGroups(playlistId)
                    main.post { groups[playlistId] = g; sync.changed(playlistId) }
                }
            }
        }
        reorderRows(p, r) { it.name }
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
        if (epgSession != session) { epgSession = session; epgOpen = false }
        val loaded = playlists.count { (epg[it.id]?.programmes ?: 0) > 0 }
        val failing = playlists.any { epg[it.id]?.let { s -> s.status != "OK" } == true }
        if (expandable) {
            dropdownRow("EPG sources", "$loaded of ${playlists.size} loaded", if (failing) Rows.Badge.PROBLEM else if (loaded == playlists.size) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE, epgOpen) {
                epgOpen = !epgOpen
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
}
