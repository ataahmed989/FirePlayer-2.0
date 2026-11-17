package app.fireplayer.live

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Updates playlists and their EPG, one job at a time on one background thread (the only writer of [LiveDb]).
 * Nothing here runs on the main thread; [listener] hears about progress on the main thread.
 *
 * Xtream Codes playlists are read through player_api.php (account details, live categories, live streams) and
 * their EPG from the server's xmltv.php unless the viewer set another. M3U playlists are read as text; their EPG is
 * the one they name (url-tvg) unless the viewer set another. Logins never appear in logs: only the playlist's name.
 */
class LiveSync private constructor(context: Context) {

    interface Listener {
        /** A playlist's channels or its EPG changed (or a sync finished with an error status). */
        fun onLiveDataChanged(playlistId: String)
    }

    private val app = context.applicationContext
    private val db = LiveDb.get(app)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerLiveSync").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val running = java.util.concurrent.atomic.AtomicInteger()

    val busy: Boolean get() = running.get() > 0

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Updates one playlist's channels, then its EPG. */
    fun update(playlistId: String, epg: Boolean = true) = submit {
        val p = db.playlist(playlistId) ?: return@submit
        syncChannels(p)
        notify(p.id)
        if (epg) {
            db.playlist(playlistId)?.let { syncEpg(it) }
            notify(p.id)
        }
    }

    /** Updates every playlist (channels first for all, then every EPG, so fallback EPG sees every channel). */
    fun updateAll() = submit {
        val all = db.playlists()
        for (p in all) { syncChannels(p); notify(p.id) }
        for (p in db.playlists()) { syncEpg(p); notify(p.id) }
    }

    /** On app start: playlists not updated within their interval are updated now. */
    fun updateDue(now: Long = System.currentTimeMillis()) = submit {
        val due = db.playlists().filter { now - it.lastSync >= it.updateHours.coerceAtLeast(1) * HOUR_MS }
        for (p in due) { syncChannels(p); notify(p.id) }
        for (p in due) { db.playlist(p.id)?.let { syncEpg(it) }; notify(p.id) }
        db.pruneProgrammes(now - PAST_KEEP_MS)
    }

    fun updateEpg(playlistId: String? = null) = submit {
        val list = if (playlistId == null) db.playlists() else listOfNotNull(db.playlist(playlistId))
        for (p in list) { syncEpg(p); notify(p.id) }
    }

    fun clearEpg() = submit {
        db.clearEpg()
        for (p in db.playlists()) notify(p.id)
    }

    private fun submit(job: () -> Unit) {
        running.incrementAndGet()
        worker.execute {
            try {
                job()
            } catch (t: Throwable) {
                Log.w(TAG, "Live sync failed: ${t.javaClass.simpleName}")
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun notify(id: String) = main.post { for (l in listeners.toList()) l.onLiveDataChanged(id) }

    // ---- Channels ----------------------------------------------------------------------------------------------

    private fun syncChannels(p: Playlist) {
        val now = System.currentTimeMillis()
        try {
            val parsed: ParsedPlaylist = when (p.kind) {
                PlaylistKind.XTREAM -> {
                    val api = XtreamApi(p)
                    val account = api.get(null)?.let { XtreamParser.account(it) }
                    if (account == null) {
                        db.setPlaylistStatus(p.id, "Couldn't sign in: check the server, username and password", syncedAt = null)
                        return
                    }
                    if (!account.active) {
                        db.setPlaylistStatus(p.id, account.message ?: "The account isn't active", syncedAt = null, expiresAt = account.expiresAt, maxConnections = account.maxConnections)
                        return
                    }
                    if (!p.includeLive) {
                        db.setPlaylistStatus(p.id, "Updated (TV channels turned off)", now, account.expiresAt, account.maxConnections)
                        ParsedPlaylist(emptyList(), emptyList())
                    } else {
                        val cats = api.get("get_live_categories") ?: "[]"
                        val streams = api.get("get_live_streams")
                        if (streams == null) {
                            db.setPlaylistStatus(p.id, "Couldn't load the channel list", syncedAt = null)
                            return
                        }
                        val result = XtreamParser.live(p.id, cats, streams) { id, _ -> api.streamUrl(id) }
                        db.setPlaylistStatus(p.id, "Updated: ${result.channels.size} channels", now, account.expiresAt, account.maxConnections)
                        result
                    }
                }
                PlaylistKind.M3U -> {
                    val text = Http.text(p.url, MAX_PLAYLIST_CHARS)
                    if (text == null) {
                        db.setPlaylistStatus(p.id, "Couldn't load the playlist", syncedAt = null)
                        return
                    }
                    val parsed = M3uParser.parse(text)
                    if (parsed.entries.isEmpty() && !text.trimStart().startsWith("#EXTM3U")) {
                        db.setPlaylistStatus(p.id, "That link isn't an M3U playlist", syncedAt = null)
                        return
                    }
                    val result = M3uParser.toPlaylist(p.id, parsed)
                    db.setPlaylistStatus(p.id, "Updated: ${result.channels.size} channels", now, playlistEpgUrl = result.epgUrl ?: "")
                    result
                }
            }
            val removed = db.applySync(p.id, parsed)
            Log.i(TAG, "Playlist \"${p.name}\": ${parsed.channels.size} channels, ${parsed.groups.size} groups, $removed removed")
        } catch (t: Throwable) {
            db.setPlaylistStatus(p.id, "Update failed", syncedAt = null)
            Log.w(TAG, "Playlist \"${p.name}\" failed: ${t.javaClass.simpleName}")
        }
    }

    // ---- EPG ---------------------------------------------------------------------------------------------------

    private fun syncEpg(p: Playlist) {
        val url = epgUrl(p) ?: return
        val now = System.currentTimeMillis()
        // The refs this source's EPG feeds: its own channels, plus those of playlists with no EPG of their own
        // (fallback by exact XMLTV id; see LiveDb.programmes).
        val withoutEpg = db.playlists().filter { it.id != p.id && epgUrl(it) == null }.map { it.id }
        val wanted = db.epgRefs(listOf(p.id) + withoutEpg)
        if (wanted.isEmpty()) {
            db.setEpgStatus(p.id, url, "No channels with EPG ids", now)
            return
        }
        try {
            val conn = Http.open(url) ?: run {
                db.setEpgStatus(p.id, url, "Couldn't load the EPG", now)
                return
            }
            try {
                val count = conn.second.use { input ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(input, null)
                    db.replaceProgrammes(p.id, url, now - PAST_KEEP_MS, now) { put ->
                        XmltvReader.read(parser, wanted, now - PAST_KEEP_MS, now + FUTURE_KEEP_MS) { item -> put(item.channel, item.programme) }
                    }
                }
                Log.i(TAG, "EPG for \"${p.name}\": $count programmes")
            } finally {
                conn.first.disconnect()
            }
        } catch (t: Throwable) {
            db.setEpgStatus(p.id, url, "The EPG couldn't be read", now)
            Log.w(TAG, "EPG for \"${p.name}\" failed: ${t.javaClass.simpleName}")
        }
    }

    private fun epgUrl(p: Playlist): String? =
        db.epgUrlFor(p) ?: if (p.kind == PlaylistKind.XTREAM) XtreamApi(p).xmltvUrl() else null

    companion object {
        private const val TAG = "FirePlayerLive"
        private const val HOUR_MS = 60L * 60 * 1000
        /** One day of past programmes is kept (design-layout: "Past days to keep EPG", one day). */
        const val PAST_KEEP_MS = 24 * HOUR_MS
        /** How far ahead programmes are kept: provisional, the design leaves the future horizon open. */
        const val FUTURE_KEEP_MS = 3 * 24 * HOUR_MS
        private const val MAX_PLAYLIST_CHARS = 32 * 1024 * 1024

        @Volatile private var instance: LiveSync? = null
        fun get(context: Context): LiveSync =
            instance ?: synchronized(this) { instance ?: LiveSync(context).also { instance = it } }
    }
}

/** Xtream Codes calls for one playlist, by its server address and login. */
class XtreamApi(private val p: Playlist) {
    private val base: String = p.url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }

    fun get(action: String?): String? {
        val b = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", p.username)
            .appendQueryParameter("password", p.password)
        if (action != null) b.appendQueryParameter("action", action)
        return Http.text(b.build().toString(), 64 * 1024 * 1024)
    }

    fun xmltvUrl(): String = Uri.parse("$base/xmltv.php").buildUpon()
        .appendQueryParameter("username", p.username)
        .appendQueryParameter("password", p.password)
        .build().toString()

    fun streamUrl(streamId: String): String {
        val ext = if (p.output == "m3u8") "m3u8" else "ts"
        return "$base/live/${Uri.encode(p.username)}/${Uri.encode(p.password)}/$streamId.$ext"
    }
}

/** Plain HTTP GETs for playlists and EPG, with gzip handled either way (header or a .gz file). */
object Http {
    private const val UA = "FirePlayer/2.0 (Linux; Android)"

    fun open(url: String): Pair<HttpURLConnection, InputStream>? {
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            // HttpURLConnection won't follow http <-> https redirects itself.
            if (code in 301..308 && hops < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                hops++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            break
        }
        val raw = BufferedInputStream(conn.inputStream, 64 * 1024)
        val gz = conn.contentEncoding.equals("gzip", true) || isGzip(raw)
        return conn to (if (gz) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw)
    }

    fun text(url: String, maxChars: Int): String? {
        val (conn, input) = runCatching { open(url) }.getOrNull() ?: return null
        return try {
            input.bufferedReader().use { r ->
                val out = StringBuilder()
                val buf = CharArray(16 * 1024)
                while (out.length < maxChars) {
                    val n = r.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
                out.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val a = input.read()
        val b = input.read()
        input.reset()
        return a == 0x1f && b == 0x8b
    }
}
