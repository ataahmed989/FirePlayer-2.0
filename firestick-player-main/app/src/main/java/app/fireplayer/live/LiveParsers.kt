package app.fireplayer.live

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One playlist's channels and groups as the provider lists them, before they are stored. */
class ParsedPlaylist(
    val groups: List<ChannelGroup>,
    val channels: List<Channel>,
    /** The EPG link the playlist itself names (M3U url-tvg / x-tvg-url), if any. */
    val epgUrl: String? = null,
)

/**
 * M3U / M3U8 playlists: the #EXTM3U header (url-tvg, x-tvg-url) and each #EXTINF's tvg-id, tvg-name, tvg-logo,
 * tvg-chno, group-title and catchup-days, paired with the stream link that follows it.
 */
object M3uParser {
    private val QUOTED = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val BARE = Regex("""([A-Za-z0-9_-]+)=([^\s",]+)""")

    class Entry(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
        val number: Int?,
        val archiveDays: Int,
        val url: String,
    )

    class Parsed(val entries: List<Entry>, val epgUrl: String?)

    fun parse(content: String): Parsed {
        val out = ArrayList<Entry>()
        var epgUrl: String? = null
        var attrs: Map<String, String>? = null
        var display: String? = null
        var extGroup: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U") -> {
                    val head = attributes(line.removePrefix("#EXTM3U"))
                    epgUrl = (head["url-tvg"] ?: head["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF") -> {
                    val extinf = line.substringAfter("EXTINF:", "")
                    // The display name follows the first comma that isn't inside a quoted attribute.
                    val comma = firstUnquotedComma(extinf)
                    attrs = attributes(if (comma >= 0) extinf.substring(0, comma) else extinf)
                    display = if (comma >= 0) extinf.substring(comma + 1).trim() else null
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter("#EXTGRP:").trim().takeIf { it.isNotEmpty() }
                line.startsWith("#") -> {}
                else -> {
                    val a = attrs
                    if (a != null) {
                        out += Entry(
                            name = a["tvg-name"]?.takeIf { it.isNotBlank() } ?: display?.takeIf { it.isNotBlank() } ?: line,
                            logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                            group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup,
                            tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                            number = a["tvg-chno"]?.toIntOrNull(),
                            archiveDays = a["catchup-days"]?.toIntOrNull() ?: a["tvg-rec"]?.toIntOrNull() ?: 0,
                            url = line,
                        )
                    }
                    attrs = null
                    display = null
                    extGroup = null
                }
            }
        }
        return Parsed(out, epgUrl)
    }

    /**
     * Channels for [playlistId] with ids that survive a refresh. An M3U has no channel ids of its own, so a channel is
     * known by its tvg-id when that is unique in the playlist, otherwise by its name and group (with a counter for
     * exact duplicates, in playlist order). The stream link is never the id: providers change links (tokens,
     * servers) without the channel changing, and favorites must follow the channel.
     */
    fun toPlaylist(playlistId: String, parsed: Parsed): ParsedPlaylist {
        val tvgCounts = parsed.entries.groupingBy { it.tvgId }.eachCount()
        val groups = LinkedHashMap<String, ChannelGroup>()
        val used = HashMap<String, Int>()
        val channels = ArrayList<Channel>(parsed.entries.size)
        for ((i, e) in parsed.entries.withIndex()) {
            val groupId = e.group?.let { g ->
                groups.getOrPut(g) { ChannelGroup(playlistId, g, g, groups.size) }.id
            }
            val base = if (e.tvgId != null && tvgCounts[e.tvgId] == 1) "t:${e.tvgId}" else "n:${norm(e.name)}|${norm(e.group ?: "")}"
            val n = used.merge(base, 1, Int::plus)!!
            val id = if (n == 1) base else "$base#$n"
            channels += Channel(
                playlistId = playlistId, id = id, number = e.number ?: (i + 1), name = e.name, logo = e.logo,
                groupId = groupId, url = e.url, epgRef = e.tvgId, archiveDays = e.archiveDays, position = i,
            )
        }
        return ParsedPlaylist(groups.values.toList(), channels, parsed.epgUrl)
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun firstUnquotedComma(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun attributes(head: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        QUOTED.findAll(head).forEach { out[it.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[2] }
        BARE.findAll(head).forEach { m ->
            val k = m.groupValues[1].lowercase(Locale.ROOT)
            if (k !in out) out[k] = m.groupValues[2]
        }
        return out
    }
}

/** Xtream Codes player_api.php answers. */
object XtreamParser {

    class Account(val active: Boolean, val expiresAt: Long, val maxConnections: Int, val activeConnections: Int, val message: String?)

    /** The account part of player_api.php's plain answer; null when it isn't one (bad server or login). */
    fun account(json: String): Account? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = root.optJSONObject("user_info") ?: return null
        val auth = info.opt("auth")?.toString() == "1"
        val status = info.optString("status", "")
        val exp = info.optString("exp_date", "").toLongOrNull()?.let { it * 1000 } ?: 0L
        return Account(
            active = auth && (status.isEmpty() || status.equals("Active", true)),
            expiresAt = exp,
            maxConnections = info.optString("max_connections", "").toIntOrNull() ?: 0,
            activeConnections = info.optString("active_cons", "").toIntOrNull() ?: 0,
            message = info.optString("message", "").takeIf { it.isNotBlank() },
        )
    }

    /** get_live_categories and get_live_streams, in the provider's order, as one playlist's groups and channels. */
    fun live(
        playlistId: String,
        categoriesJson: String,
        streamsJson: String,
        streamUrl: (streamId: String, extension: String?) -> String,
    ): ParsedPlaylist {
        val groups = ArrayList<ChannelGroup>()
        val known = HashSet<String>()
        runCatching { JSONArray(categoriesJson) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("category_id").takeIf { it.isNotBlank() } ?: continue
                if (known.add(id)) groups += ChannelGroup(playlistId, id, o.optString("category_name", id), groups.size)
            }
        }
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        runCatching { JSONArray(streamsJson) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                if (!seen.add(id)) continue
                val cat = o.opt("category_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val epg = o.optString("epg_channel_id", "").takeIf { it.isNotBlank() && it != "null" }
                channels += Channel(
                    playlistId = playlistId,
                    id = id,
                    number = o.optString("num", "").toIntOrNull() ?: (channels.size + 1),
                    name = o.optString("name", id).trim(),
                    logo = o.optString("stream_icon", "").takeIf { it.isNotBlank() && it != "null" },
                    groupId = cat?.takeIf { it in known },
                    url = streamUrl(id, o.optString("container_extension", "").takeIf { it.isNotBlank() && it != "null" }),
                    epgRef = epg,
                    archiveDays = if (o.optString("tv_archive", "0") == "1") o.optString("tv_archive_duration", "0").toIntOrNull() ?: 0 else 0,
                    position = channels.size,
                )
            }
        }
        return ParsedPlaylist(groups, channels)
    }
}

/**
 * Streams an XMLTV document, keeping only programmes on channels in [wanted] that overlap [keepFrom, keepUntil):
 * past history beyond the retention window and far-future listings are dropped as they are read, so a big EPG
 * never has to fit in memory and old programmes are never stored (not even for a moment before a cleanup).
 */
object XmltvReader {
    private val FORMATS = arrayOf("yyyyMMddHHmmss", "yyyyMMddHHmm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    private val OFFSET = Regex("""([+-]\d{2}:?\d{2}|Z)\s*$""")

    class Item(val channel: String, val programme: Programme)

    /** Reads programmes until the end, handing each kept one to [onProgramme]; returns how many were kept. */
    fun read(
        parser: XmlPullParser,
        wanted: Set<String>,
        keepFrom: Long,
        keepUntil: Long,
        offsetMs: Long = 0,
        onProgramme: (Item) -> Unit,
    ): Int {
        var kept = 0
        var ref: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var inProgramme = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "programme" -> {
                        ref = parser.getAttributeValue(null, "channel")
                        inProgramme = ref != null && ref in wanted
                        if (inProgramme) {
                            start = epoch(parser.getAttributeValue(null, "start")) + offsetMs
                            stop = epoch(parser.getAttributeValue(null, "stop")) + offsetMs
                            title = null; desc = null; category = null
                        }
                    }
                    "title" -> if (inProgramme && title == null) title = text(parser)
                    "desc" -> if (inProgramme && desc == null) desc = text(parser)
                    "category" -> if (inProgramme && category == null) category = text(parser)
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                val r = ref
                if (inProgramme && r != null && start > 0 && stop > start && stop > keepFrom && start < keepUntil) {
                    onProgramme(Item(r, Programme(start, stop, title?.takeIf { it.isNotBlank() } ?: "", desc?.takeIf { it.isNotBlank() }, category)))
                    kept++
                }
                inProgramme = false
                ref = null
            }
            event = parser.next()
        }
        return kept
    }

    /** An XMLTV time as epoch millis; 0 when it can't be read. */
    fun epoch(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val t = text.trim()
        val m = OFFSET.find(t)
        val body = if (m != null) t.substring(0, m.range.first).trim() else t
        var offsetMin = 0
        if (m != null && m.groupValues[1] != "Z") {
            val off = m.groupValues[1]
            val d = off.substring(1).replace(":", "")
            if (d.length == 4) {
                val hh = d.substring(0, 2).toIntOrNull() ?: 0
                val mm = d.substring(2, 4).toIntOrNull() ?: 0
                offsetMin = (if (off.startsWith('-')) -1 else 1) * (hh * 60 + mm)
            }
        }
        for (format in FORMATS) {
            if (format.replace("'", "").length != body.length) continue
            val df = SimpleDateFormat(format, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { df.parse(body) }.getOrNull() ?: continue
            return parsed.time - offsetMin * 60_000L
        }
        return 0L
    }

    private fun text(parser: XmlPullParser): String {
        val out = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> out.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return out.toString().trim()
    }
}
