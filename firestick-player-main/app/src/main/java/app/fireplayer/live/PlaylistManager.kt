package app.fireplayer.live

import android.content.Context
import android.net.Uri
import android.util.Log
import app.fireplayer.live.LiveDb.Channel
import app.fireplayer.live.LiveDb.ChannelEpgMap
import app.fireplayer.live.LiveDb.ChannelGroup
import app.fireplayer.live.LiveDb.Playlist
import app.fireplayer.live.LiveDb.PlaylistKind
import app.fireplayer.player.XtreamAccount
import java.net.HttpURLConnection
import java.net.URL

/**
 * Refreshes every playlist's channels, groups and EPG mapping into [LiveDb]. M3U and M3U8 are
 * parsed here; Xtream Codes is read as its live M3U (`get.php`), with the account's credentials
 * taken by [XtreamAccount.login]. Its JSON `player_api.php` stays with Smart Play, so its HTTP is
 * not duplicated here. Channel names come from the playlist each sync; there is no manual rename.
 */
class PlaylistManager(context: Context) {

    private val db = LiveDb(context.applicationContext)

    /** One refresh cycle over every playlist. Blocks on the network; run it off the main thread. */
    fun sync(headers: Map<String, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        for (playlist in db.playlists()) {
            val url = when (playlist.kind) {
                PlaylistKind.M3U -> playlist.urlOrCreds
                PlaylistKind.XTREAM -> xtreamM3uUrl(playlist.urlOrCreds) ?: continue
            }
            val content = fetchText(url, headers) ?: continue
            val result = ingest(playlist, parseM3u(content))
            db.applySync(playlist.copy(lastSync = now), result.groups, result.channels, result.maps)
        }
    }

    /** One playlist's parsed channels, turned into groups, channels and EPG refs, in playlist order. */
    private fun ingest(playlist: Playlist, parsed: List<ParsedChannel>): SyncResult {
        val groups = ArrayList<ChannelGroup>()
        val seen = HashMap<String, Int>()
        val channels = ArrayList<Channel>()
        val maps = ArrayList<ChannelEpgMap>()
        for ((position, c) in parsed.withIndex()) {
            val groupId = c.group
            if (groupId != null && !seen.containsKey(groupId)) {
                seen[groupId] = groups.size
                groups += ChannelGroup(playlist.id, groupId, groupId, groups.size)
            }
            channels += Channel(playlist.id, c.url, c.name, c.logo, groupId, position)
            c.tvgId?.let { maps += ChannelEpgMap(playlist.id, c.url, it) }
        }
        return SyncResult(groups, channels, maps)
    }

    private class SyncResult(val groups: List<ChannelGroup>, val channels: List<Channel>, val maps: List<ChannelEpgMap>)

    /** The live M3U of an Xtream account, from a "…/live/user/pass/1" style credential URI. */
    private fun xtreamM3uUrl(creds: String): String? {
        val uri = Uri.parse(creds)
        val login = XtreamAccount.login(uri) ?: return null
        return Uri.Builder().scheme(uri.scheme).authority(uri.authority)
            .appendPath("get.php")
            .appendQueryParameter("username", login.first)
            .appendQueryParameter("password", login.second)
            .appendQueryParameter("type", "m3u_plus")
            .appendQueryParameter("output", "ts")
            .build().toString()
    }

    /** The playlist text at [url], capped; null when it can't be fetched. */
    private fun fetchText(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "FirePlayer/1.0 (Linux; Android)"
            conn.setRequestProperty("User-Agent", userAgent)
            headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "The playlist answered ${conn.responseCode}")
                    return null
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(8 * 1024)
                    while (out.length < MAX_PLAYLIST_CHARS) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        out.append(buffer, 0, n)
                    }
                    out.toString()
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.i(TAG, "Couldn't fetch the playlist: $it") }.getOrNull()
    }

    companion object {
        private const val TAG = "FirePlayerPlaylist"
        private const val MAX_PLAYLIST_CHARS = 16 * 1024 * 1024
        private val QUOTED = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
        private val BARE = Regex("""([A-Za-z0-9_-]+)=([^\s",]+)""")

        /** One parsed EXTINF entry. */
        class ParsedChannel(
            val name: String,
            val logo: String?,
            val group: String?,
            val tvgId: String?,
            val url: String,
        )

        /**
         * Parses an M3U / M3U8 document: the #EXTM3U header, then each #EXTINF's tvg-id, tvg-name,
         * tvg-logo and group-title, paired with the stream URL that follows it.
         */
        fun parseM3u(content: String): List<ParsedChannel> {
            val out = ArrayList<ParsedChannel>()
            var attrs: Map<String, String>? = null
            var display: String? = null
            for (raw in content.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#EXTM3U") -> { attrs = null; display = null }
                    line.startsWith("#EXTINF") -> {
                        val extinf = line.substringAfter("EXTINF:", "")
                        attrs = attributes(extinf.substringBefore(','))
                        display = extinf.substringAfter(',', "").trim()
                    }
                    line.startsWith("#") -> {}
                    else -> {
                        val url = line
                        if (attrs != null) {
                            out += ParsedChannel(
                                name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: display?.takeIf { it.isNotBlank() } ?: url,
                                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                group = attrs["group-title"]?.takeIf { it.isNotBlank() },
                                tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                                url = url,
                            )
                        }
                        attrs = null
                        display = null
                    }
                }
            }
            return out
        }

        private fun attributes(head: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            QUOTED.findAll(head).forEach { out[it.groupValues[1]] = it.groupValues[2] }
            BARE.findAll(head).forEach { m -> if (m.groupValues[1] !in out) out[m.groupValues[1]] = m.groupValues[2] }
            return out
        }
    }
}
