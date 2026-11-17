package app.fireplayer.live

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The one app-wide merged XMLTV source. Programmes are stored keyed by their XMLTV channel id
 * ([program.epg_ref]); a playlist channel reaches its EPG through [LiveDb]'s `channel_epg_map`,
 * never through `<channel>` display names, and a channel with no mapping shows no EPG.
 *
 * Ingestion streams the file (pull parser) and keeps one day of history: every ingest deletes
 * programmes that ended more than 24 hours ago.
 */
class EpgStore(context: Context) {

    private val live = LiveDb(context.applicationContext)

    /** The entry point [PlaylistManager] calls after its own sync: ingest the app-wide EPG URL. */
    fun sync() {
        val db = live.writableDatabase
        ensureSchema(db)
        val url = db.rawQuery("SELECT epg_url FROM playlist WHERE epg_url IS NOT NULL AND epg_url != '' LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        ingest(url)
    }

    /** Streams one XMLTV document at [url], storing programmes whose channel is mapped. */
    fun ingest(url: String) {
        val db = live.writableDatabase
        ensureSchema(db)
        val known = knownEpgRefs(db)
        if (known.isEmpty()) return

        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "FirePlayer/1.0 (Linux; Android)")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "The EPG source answered ${conn.responseCode}")
                return
            }
            conn.inputStream.buffered().use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)

                db.beginTransaction()
                try {
                    db.delete("program", "stop < ?", arrayOf((System.currentTimeMillis() - DAY_MS).toString()))
                    val insert = db.compileStatement("INSERT OR REPLACE INTO program(epg_ref, start, stop, title, description, category) VALUES (?, ?, ?, ?, ?, ?)")

                    var inProgramme = false
                    var ref: String? = null
                    var start = 0L
                    var stop = 0L
                    var title: String? = null
                    var desc: String? = null
                    var category: String? = null

                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "programme" -> {
                                    inProgramme = true
                                    ref = parser.getAttributeValue(null, "channel")
                                    start = epoch(parser.getAttributeValue(null, "start"))
                                    stop = epoch(parser.getAttributeValue(null, "stop"))
                                    title = null; desc = null; category = null
                                }
                                "title" -> if (inProgramme) title = readText(parser)
                                "desc" -> if (inProgramme) desc = readText(parser)
                                "category" -> if (inProgramme && category == null) category = readText(parser)
                            }
                            XmlPullParser.END_TAG -> if (parser.name == "programme") {
                                if (inProgramme && ref != null && ref in known) {
                                    insert.clearBindings()
                                    insert.bindString(1, ref)
                                    insert.bindLong(2, start)
                                    insert.bindLong(3, stop)
                                    title?.let { insert.bindString(4, it) } ?: insert.bindNull(4)
                                    desc?.let { insert.bindString(5, it) } ?: insert.bindNull(5)
                                    category?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
                                    insert.executeInsert()
                                }
                                inProgramme = false
                                ref = null
                            }
                        }
                        event = parser.next()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** The [program] table and the playlist `epg_url` column, created once when missing. */
    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS program(epg_ref TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, description TEXT, category TEXT, PRIMARY KEY(epg_ref, start, stop)) WITHOUT ROWID")
        if (!hasColumn(db, "playlist", "epg_url")) db.execSQL("ALTER TABLE playlist ADD COLUMN epg_url TEXT")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) found = true
            found
        }

    /** The XMLTV channel ids a playlist channel is mapped to: only these are stored. */
    private fun knownEpgRefs(db: SQLiteDatabase): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT DISTINCT epg_ref FROM channel_epg_map WHERE epg_ref IS NOT NULL AND epg_ref != ''", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    companion object {
        private const val TAG = "FirePlayerEpg"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val TIME_FORMATS = arrayOf(
            "yyyyMMddHHmmss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
        )
        private val OFFSET = Regex("""([+-]\d{2}:?\d{2}|Z)\s*$""")

        /** An XMLTV time as epoch millis; 0 when it can't be read. */
        private fun epoch(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            val (body, offsetMin) = splitOffset(text.trim())
            for (format in TIME_FORMATS) {
                val df = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                try {
                    df.parse(body)?.let { return it.time - offsetMin * 60_000L }
                } catch (_: Exception) {
                }
            }
            return 0L
        }

        /** Splits a trailing "Z" or "+HH:MM" / "+HHMM" offset off an XMLTV time. */
        private fun splitOffset(text: String): Pair<String, Int> {
            val m = OFFSET.find(text) ?: return text to 0
            val off = m.groupValues[1]
            val body = text.substring(0, m.range.first).trim()
            if (off == "Z") return body to 0
            val sign = if (off.startsWith('-')) -1 else 1
            val d = off.substring(1).replace(":", "")
            if (d.length != 4) return body to 0
            val hh = d.substring(0, 2).toIntOrNull() ?: return body to 0
            val mm = d.substring(2, 4).toIntOrNull() ?: return body to 0
            return body to sign * (hh * 60 + mm)
        }

        /** The text of the element the parser is on, past any nested tags, up to its close. */
        private fun readText(parser: XmlPullParser): String {
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
}
