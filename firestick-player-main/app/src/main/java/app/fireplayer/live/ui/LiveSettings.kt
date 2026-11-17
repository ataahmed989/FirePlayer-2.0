package app.fireplayer.live.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.PlaylistKind
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.SidePanel
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live TV's settings windows (design-layout, "Live settings — user outline, incomplete"): Playlists (each with its
 * name, Xtream parameters or M3U link, EPG source, account details, update interval, Update and Delete), EPG
 * (status, past days kept, Update, Clear) and Live TV (channel changing buttons, guide inactivity). Shown in the same
 * SidePanel as every other setting. Saved passwords are never shown: only whether one is set.
 *
 * Not here yet (outline items still to build): Manage groups, Reorder playlists, M3U-specific fields beyond the link.
 */
class LiveSettings(private val activity: Activity, private val panel: SidePanel) {

    private val db = LiveDb.get(activity)
    private val sync = LiveSync.get(activity)
    private val prefs = LivePrefs(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var playlists: List<Playlist> = emptyList()

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

    private fun reloadThen(then: () -> Unit) {
        io.execute {
            val list = db.playlists()
            main.post { playlists = list; then() }
        }
    }

    // ---- Playlists ------------------------------------------------------------------------------------------------

    fun openPlaylists(onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Playlists", onClose) { renderPlaylists(this, onClose) }
    }

    private fun renderPlaylists(p: SidePanel, onClose: (() -> Unit)?) = with(p) {
        if (playlists.isEmpty()) message("Add an Xtream Codes account or an M3U playlist link. Channels and the TV guide load from it.")
        for (pl in playlists) {
            row(pl.name, shortStatus(pl), twoLines = false) { openPlaylist(pl.id, onClose) }
        }
        header("Manage")
        row("Add playlist") { addPlaylist(onClose) }
        if (playlists.isNotEmpty()) {
            row("Update all playlists", if (sync.busy) "Updating…" else null) { sync.updateAll(); refresh() }
        }
    }

    private fun shortStatus(p: Playlist): String = when {
        p.lastStatus.isBlank() -> "Not updated yet"
        p.lastStatus.startsWith("Updated: ") -> p.lastStatus.removePrefix("Updated: ")
        else -> p.lastStatus
    }

    private fun openPlaylist(id: String, parentClose: (() -> Unit)?) {
        val back = { openPlaylists(parentClose) }
        reloadThen {
            val pl = playlists.firstOrNull { it.id == id } ?: return@reloadThen back()
            panel.open(pl.name, onClose = { main.post { back() } }) { renderPlaylist(this, id) }
        }
    }

    private fun renderPlaylist(p: SidePanel, id: String) = with(p) {
        val pl = playlists.firstOrNull { it.id == id } ?: run { message("This playlist was deleted."); return@with }
        val tf = DateFormat.getTimeFormat(activity)
        val when_ = if (pl.lastSync > 0) "${DateFormat.getMediumDateFormat(activity).format(Date(pl.lastSync))} ${tf.format(Date(pl.lastSync))}" else "never"
        message(if (sync.busy) "Updating…" else "${pl.lastStatus.ifBlank { "Not updated yet" }} (last update: $when_)")

        header("Playlist")
        row("Name", pl.name) { edit("Name", pl.name) { v -> save(pl.copy(name = v.ifBlank { pl.name })) } }
        if (pl.kind == PlaylistKind.XTREAM) {
            header("Xtream Codes parameters")
            row("Server", pl.url) { edit("Server address", pl.url, uri = true) { v -> save(pl.copy(url = v.trim())) } }
            row("Username", pl.username) { edit("Username", pl.username) { v -> save(pl.copy(username = v.trim())) } }
            row("Password", if (pl.password.isNotEmpty()) "Set" else "Not set") {
                SidePanel.prompt(activity, "Password", "", password = true) { v -> if (v.isNotEmpty()) save(pl.copy(password = v)) }
            }
            choice("Output format", listOf("MPEG-TS (.ts)", "HLS (.m3u8)"), if (pl.output == "m3u8") 1 else 0) { i ->
                save(pl.copy(output = if (i == 1) "m3u8" else "ts"))
            }
            row("Include TV channels", if (pl.includeLive) "On" else "Off") { save(pl.copy(includeLive = !pl.includeLive)) }
            row("Include VOD", if (pl.includeVod) "On" else "Off") { save(pl.copy(includeVod = !pl.includeVod)) }
            header("Account")
            // Read-only, as the provider reports them: the allowance isn't the streams in use or what Multiview can do.
            message(buildString {
                append("Expires: ").append(if (pl.expiresAt > 0) DateFormat.getMediumDateFormat(activity).format(Date(pl.expiresAt)) else "not reported")
                append("\nConnections allowed: ").append(if (pl.maxConnections > 0) pl.maxConnections.toString() else "not reported")
            })
        } else {
            header("M3U playlist")
            row("Playlist link", pl.url) { edit("Playlist link", pl.url, uri = true) { v -> save(pl.copy(url = v.trim())) } }
        }
        header("EPG sources")
        row("EPG link", pl.epgUrl.ifBlank { if (pl.kind == PlaylistKind.XTREAM) "The server's own" else "The playlist's own" }) {
            edit("EPG link (blank: the playlist's own)", pl.epgUrl, uri = true) { v -> save(pl.copy(epgUrl = v.trim())) }
        }
        header("Updates")
        val hours = listOf(6, 12, 24, 48)
        choice("Update interval", hours.map { "Every $it hours" }, hours.indexOf(pl.updateHours).let { if (it < 0) 2 else it }) { i ->
            save(pl.copy(updateHours = hours[i]))
        }
        row("Update playlist") { sync.update(pl.id); refresh() }
        row("Delete playlist") {
            FireDialog.ask(activity, "Delete \"${pl.name}\"? Its channels, favorites and guide go too.", listOf(
                "Delete" to {
                    io.execute {
                        db.deletePlaylist(pl.id)
                        main.post { panel.close() }
                    }
                },
                "Cancel" to {},
            ))
        }
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
                panel.refresh()
                if (before == null || before.url != p.url || before.username != p.username || before.password != p.password ||
                    before.output != p.output || before.includeLive != p.includeLive || before.epgUrl != p.epgUrl
                ) sync.update(p.id)
            }
        }
    }

    private fun addPlaylist(onClose: (() -> Unit)?) {
        FireDialog.ask(activity, "Add playlist", listOf(
            "Xtream Codes" to { addXtream(onClose) },
            "M3U playlist link" to { addM3u(onClose) },
        ))
    }

    private fun addXtream(onClose: (() -> Unit)?) {
        edit("Server address (for example http://example.com:8080)", "", uri = true) { server ->
            if (server.isBlank()) return@edit
            edit("Username", "") { user ->
                SidePanel.prompt(activity, "Password", "", password = true) { pass ->
                    val url = normalized(server)
                    create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.XTREAM, url, user.trim(), pass, position = playlists.size), onClose)
                }
            }
        }
    }

    private fun addM3u(onClose: (() -> Unit)?) {
        edit("Playlist link", "", uri = true) { link ->
            if (link.isBlank()) return@edit
            val url = normalized(link)
            create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.M3U, url, position = playlists.size), onClose)
        }
    }

    private fun create(p: Playlist, onClose: (() -> Unit)?) {
        io.execute {
            db.savePlaylist(p)
            main.post {
                sync.update(p.id)
                openPlaylist(p.id, onClose)
            }
        }
    }

    private fun normalized(s: String): String {
        val v = s.trim()
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(v)) v else "http://$v"
    }

    private fun hostName(url: String): String = Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Playlist"

    // ---- EPG ------------------------------------------------------------------------------------------------------

    fun openEpg(onClose: (() -> Unit)? = null) = reloadThen {
        val statuses = db.epgStatuses().associateBy { it.source }
        panel.open("EPG", onClose) {
            message("Each playlist's guide comes from its own EPG. A playlist without one borrows another playlist's listings only for channels with the exact same EPG id.")
            header("EPG sources")
            if (playlists.isEmpty()) message("No playlists yet.")
            val tf = DateFormat.getTimeFormat(activity)
            for (pl in playlists) {
                val s = statuses[pl.id]
                val value = when {
                    s == null -> "Not loaded"
                    s.status == "OK" -> "${s.programmes} programmes · ${tf.format(Date(s.lastSync))}"
                    else -> s.status
                }
                row(pl.name, value) {}
            }
            header("Storage")
            // Provisional: the outline fixes one day of past programmes; whether it's adjustable is still open.
            row("Past days to keep EPG", "1 day", enabled = false) {}
            header("Update")
            row("Update EPG", if (sync.busy) "Updating…" else null) { sync.updateEpg(); refresh() }
            row("Clear EPG") {
                FireDialog.ask(activity, "Clear all programme listings? They load again at the next update.", listOf(
                    "Clear" to { sync.clearEpg() },
                    "Cancel" to {},
                ))
            }
        }
    }

    // ---- Live TV --------------------------------------------------------------------------------------------------

    fun openLiveOptions(onClose: (() -> Unit)? = null) {
        panel.open("Live TV", onClose) {
            header("Remote")
            choice("Channel changing buttons", listOf("D-pad + channel buttons", "Channel buttons only"), if (prefs.channelButtonsOnly) 1 else 0) { i ->
                prefs.channelButtonsOnly = i == 1
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
