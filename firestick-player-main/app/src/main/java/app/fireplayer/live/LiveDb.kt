package app.fireplayer.live

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Live TV storage (`live.db`): playlists, groups, channels, favorites, recent channels and EPG programmes.
 *
 * Ownership: only the main app process opens this database (the title service in `:titles` never does), through the
 * one [get] instance. Writes happen on [LiveSync]'s single worker thread; the UI reads on its own background
 * loader. WAL lets those reads go on while a sync writes, and one writer means no write contention.
 *
 * A sync rewrites the provider's columns of each channel (name, number, logo, group, link, EPG id) and removes
 * channels and groups the provider no longer lists. The viewer's own data lives apart: favorites in their own table
 * keyed by playlist + channel id, so they survive refreshes and come back if a removed channel returns.
 */
class LiveDb private constructor(context: Context) : SQLiteOpenHelper(context, "live.db", null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlist(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, url TEXT NOT NULL, " +
                "username TEXT NOT NULL DEFAULT '', password TEXT NOT NULL DEFAULT '', epg_url TEXT NOT NULL DEFAULT '', " +
                "output TEXT NOT NULL DEFAULT 'ts', include_live INTEGER NOT NULL DEFAULT 1, include_vod INTEGER NOT NULL DEFAULT 1, " +
                "position INTEGER NOT NULL DEFAULT 0, update_hours INTEGER NOT NULL DEFAULT 24, last_sync INTEGER NOT NULL DEFAULT 0, " +
                "last_status TEXT NOT NULL DEFAULT '', expires_at INTEGER NOT NULL DEFAULT 0, max_connections INTEGER NOT NULL DEFAULT 0, " +
                "playlist_epg_url TEXT NOT NULL DEFAULT '') WITHOUT ROWID",
        )
        db.execSQL("CREATE TABLE channel_group(playlist_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID")
        db.execSQL(
            "CREATE TABLE channel(playlist_id TEXT NOT NULL, id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, " +
                "group_id TEXT, url TEXT NOT NULL, epg_ref TEXT, archive_days INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL, " +
                "blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID",
        )
        db.execSQL("CREATE INDEX channel_group_idx ON channel(playlist_id, group_id, position)")
        db.execSQL("CREATE TABLE favorite(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE recent(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
        // Programmes are kept per EPG source: the playlist whose EPG listed them.
        db.execSQL(
            "CREATE TABLE program(source TEXT NOT NULL, epg_ref TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, description TEXT, category TEXT, PRIMARY KEY(source, epg_ref, start)) WITHOUT ROWID",
        )
        db.execSQL("CREATE INDEX program_ref_idx ON program(epg_ref, start)")
        db.execSQL("CREATE TABLE epg_status(source TEXT PRIMARY KEY, url TEXT NOT NULL, last_sync INTEGER NOT NULL, status TEXT NOT NULL, programmes INTEGER NOT NULL) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 was groundwork that no build ever opened (nothing called it), so no viewer has data in it.
        if (oldVersion < 2) {
            for (t in listOf("program", "channel_epg_map", "channel", "channel_group", "playlist", "favorite", "recent", "epg_status")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }
    }

    // ---- Playlists --------------------------------------------------------------------------------------------

    fun playlists(): List<Playlist> =
        readableDatabase.rawQuery("SELECT $PLAYLIST_COLS FROM playlist ORDER BY position, name", null).use { c ->
            buildList { while (c.moveToNext()) add(playlistAt(c)) }
        }

    fun playlist(id: String): Playlist? =
        readableDatabase.rawQuery("SELECT $PLAYLIST_COLS FROM playlist WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) playlistAt(c) else null
        }

    /** Adds or edits a playlist's own settings. Sync results (status, account, the playlist's EPG link) are kept. */
    fun savePlaylist(p: Playlist) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("name", p.name); put("kind", p.kind.code); put("url", p.url); put("username", p.username)
            put("password", p.password); put("epg_url", p.epgUrl); put("output", p.output)
            put("include_live", if (p.includeLive) 1 else 0); put("include_vod", if (p.includeVod) 1 else 0)
            put("position", p.position); put("update_hours", p.updateHours)
        }
        if (db.update("playlist", v, "id = ?", arrayOf(p.id)) == 0) {
            v.put("id", p.id)
            db.insertOrThrow("playlist", null, v)
        }
    }

    fun deletePlaylist(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("channel", "channel_group", "favorite", "recent")) db.delete(t, "playlist_id = ?", arrayOf(id))
            db.delete("program", "source = ?", arrayOf(id))
            db.delete("epg_status", "source = ?", arrayOf(id))
            db.delete("playlist", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setPlaylistStatus(id: String, status: String, syncedAt: Long?, expiresAt: Long? = null, maxConnections: Int? = null, playlistEpgUrl: String? = null) {
        val v = ContentValues().apply {
            put("last_status", status)
            syncedAt?.let { put("last_sync", it) }
            expiresAt?.let { put("expires_at", it) }
            maxConnections?.let { put("max_connections", it) }
            playlistEpgUrl?.let { put("playlist_epg_url", it) }
        }
        writableDatabase.update("playlist", v, "id = ?", arrayOf(id))
    }

    /** The EPG link a playlist's guide comes from: the viewer's own, else the one the playlist names. */
    fun epgUrlFor(p: Playlist): String? {
        if (p.epgUrl.isNotBlank()) return p.epgUrl
        val named = readableDatabase.rawQuery("SELECT playlist_epg_url FROM playlist WHERE id = ?", arrayOf(p.id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return named?.takeIf { it.isNotBlank() }
    }

    // ---- Sync --------------------------------------------------------------------------------------------------

    /**
     * Stores one playlist's channels and groups as just listed by the provider. Provider columns are rewritten;
     * channels and groups no longer listed are removed (their favorites stay in their own table, harmlessly, until
     * the channel returns or the playlist is deleted). Returns how many channels were removed.
     */
    fun applySync(playlistId: String, parsed: ParsedPlaylist): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS seen_channel(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS seen_group(id TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM seen_channel")
            db.execSQL("DELETE FROM seen_group")

            // UPDATE then INSERT OR IGNORE, not "ON CONFLICT DO UPDATE": that needs SQLite 3.24, and Fire OS 5
            // (API 22, still supported) ships 3.8.
            val setGroup = db.compileStatement("UPDATE channel_group SET name = ?, position = ? WHERE playlist_id = ? AND id = ?")
            val addGroup = db.compileStatement("INSERT OR IGNORE INTO channel_group(playlist_id, id, name, position) VALUES (?, ?, ?, ?)")
            val seenGroup = db.compileStatement("INSERT OR IGNORE INTO seen_group(id) VALUES (?)")
            for (g in parsed.groups) {
                setGroup.clearBindings()
                setGroup.bindString(1, g.name); setGroup.bindLong(2, g.position.toLong()); setGroup.bindString(3, playlistId); setGroup.bindString(4, g.id)
                if (setGroup.executeUpdateDelete() == 0) {
                    addGroup.clearBindings()
                    addGroup.bindString(1, playlistId); addGroup.bindString(2, g.id); addGroup.bindString(3, g.name); addGroup.bindLong(4, g.position.toLong())
                    addGroup.executeInsert()
                }
                seenGroup.clearBindings(); seenGroup.bindString(1, g.id); seenGroup.executeInsert()
            }

            val setChannel = db.compileStatement(
                "UPDATE channel SET number = ?, name = ?, logo = ?, group_id = ?, url = ?, epg_ref = ?, archive_days = ?, position = ? " +
                    "WHERE playlist_id = ? AND id = ?",
            )
            val addChannel = db.compileStatement(
                "INSERT OR IGNORE INTO channel(playlist_id, id, number, name, logo, group_id, url, epg_ref, archive_days, position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            val seenChannel = db.compileStatement("INSERT OR IGNORE INTO seen_channel(id) VALUES (?)")
            for (c in parsed.channels) {
                setChannel.clearBindings()
                setChannel.bindLong(1, c.number.toLong()); setChannel.bindString(2, c.name); bindOrNull(setChannel, 3, c.logo)
                bindOrNull(setChannel, 4, c.groupId); setChannel.bindString(5, c.url); bindOrNull(setChannel, 6, c.epgRef)
                setChannel.bindLong(7, c.archiveDays.toLong()); setChannel.bindLong(8, c.position.toLong())
                setChannel.bindString(9, playlistId); setChannel.bindString(10, c.id)
                if (setChannel.executeUpdateDelete() == 0) {
                    addChannel.clearBindings()
                    addChannel.bindString(1, playlistId); addChannel.bindString(2, c.id); addChannel.bindLong(3, c.number.toLong())
                    addChannel.bindString(4, c.name); bindOrNull(addChannel, 5, c.logo); bindOrNull(addChannel, 6, c.groupId)
                    addChannel.bindString(7, c.url); bindOrNull(addChannel, 8, c.epgRef); addChannel.bindLong(9, c.archiveDays.toLong())
                    addChannel.bindLong(10, c.position.toLong())
                    addChannel.executeInsert()
                }
                seenChannel.clearBindings(); seenChannel.bindString(1, c.id); seenChannel.executeInsert()
            }

            val removed = db.delete("channel", "playlist_id = ? AND id NOT IN (SELECT id FROM seen_channel)", arrayOf(playlistId))
            db.delete("channel_group", "playlist_id = ? AND id NOT IN (SELECT id FROM seen_group)", arrayOf(playlistId))
            db.delete("recent", "playlist_id = ? AND channel_id NOT IN (SELECT id FROM seen_channel)", arrayOf(playlistId))
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    /** The XMLTV ids whose programmes a source's EPG should keep (see [EpgSync]). */
    fun epgRefs(playlistIds: Collection<String>): Set<String> {
        if (playlistIds.isEmpty()) return emptySet()
        val marks = playlistIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT DISTINCT epg_ref FROM channel WHERE epg_ref IS NOT NULL AND epg_ref != '' AND playlist_id IN ($marks)",
            playlistIds.toTypedArray(),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /**
     * Replaces everything [source]'s EPG listed with what [fill] inserts, in one transaction, and drops every
     * source's programmes that ended before [keepFrom]. Readers keep seeing the old listings until it commits.
     */
    fun replaceProgrammes(source: String, url: String, keepFrom: Long, now: Long, fill: ((String, Programme) -> Unit) -> Int): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("program", "source = ?", arrayOf(source))
            db.delete("program", "stop < ?", arrayOf(keepFrom.toString()))
            val insert = db.compileStatement(
                "INSERT OR REPLACE INTO program(source, epg_ref, start, stop, title, description, category) VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            val count = fill { ref, p ->
                insert.clearBindings()
                insert.bindString(1, source); insert.bindString(2, ref); insert.bindLong(3, p.start); insert.bindLong(4, p.stop)
                insert.bindString(5, p.title); bindOrNull(insert, 6, p.description); bindOrNull(insert, 7, p.category)
                insert.executeInsert()
            }
            val v = ContentValues().apply {
                put("source", source); put("url", url); put("last_sync", now); put("status", "OK"); put("programmes", count)
            }
            db.insertWithOnConflict("epg_status", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun setEpgStatus(source: String, url: String, status: String, now: Long) {
        val v = ContentValues().apply { put("url", url); put("status", status); put("last_sync", now) }
        if (writableDatabase.update("epg_status", v, "source = ?", arrayOf(source)) == 0) {
            v.put("source", source); v.put("programmes", 0)
            writableDatabase.insert("epg_status", null, v)
        }
    }

    class EpgStatus(val source: String, val url: String, val lastSync: Long, val status: String, val programmes: Int)

    fun epgStatuses(): List<EpgStatus> =
        readableDatabase.rawQuery("SELECT source, url, last_sync, status, programmes FROM epg_status", null).use { c ->
            buildList { while (c.moveToNext()) add(EpgStatus(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4))) }
        }

    fun clearEpg() {
        val db = writableDatabase
        db.delete("program", null, null)
        db.delete("epg_status", null, null)
    }

    fun pruneProgrammes(keepFrom: Long) {
        writableDatabase.delete("program", "stop < ?", arrayOf(keepFrom.toString()))
    }

    // ---- Guide reads ---------------------------------------------------------------------------------------------

    fun groups(playlistId: String): List<ChannelGroup> =
        readableDatabase.rawQuery("SELECT id, name, position FROM channel_group WHERE playlist_id = ? ORDER BY position", arrayOf(playlistId)).use { c ->
            buildList { while (c.moveToNext()) add(ChannelGroup(playlistId, c.getString(0), c.getString(1), c.getInt(2))) }
        }

    /** The channels a guide scope lists, in guide order. Blocked channels are left out. */
    fun channels(scope: GuideScope): List<Channel> {
        val select = "SELECT c.playlist_id, c.id, c.number, c.name, c.logo, c.group_id, c.url, c.epg_ref, c.archive_days, c.position, " +
            "f.channel_id IS NOT NULL, c.blocked FROM channel c LEFT JOIN favorite f ON f.playlist_id = c.playlist_id AND f.channel_id = c.id "
        val (sql, args) = when (scope) {
            GuideScope.AllPlaylists ->
                (select + "JOIN playlist p ON p.id = c.playlist_id WHERE c.blocked = 0 ORDER BY p.position, p.name, c.position") to emptyArray<String>()
            is GuideScope.AllChannels ->
                (select + "WHERE c.playlist_id = ? AND c.blocked = 0 ORDER BY c.position") to arrayOf(scope.playlistId)
            is GuideScope.Favorites ->
                (select + "WHERE c.playlist_id = ? AND f.channel_id IS NOT NULL AND c.blocked = 0 ORDER BY f.added, c.position") to arrayOf(scope.playlistId)
            is GuideScope.Group ->
                (select + "WHERE c.playlist_id = ? AND c.group_id = ? AND c.blocked = 0 ORDER BY c.position") to arrayOf(scope.playlistId, scope.groupId)
        }
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(channelAt(c)) } }
    }

    fun channel(key: ChannelKey): Channel? =
        readableDatabase.rawQuery(
            "SELECT c.playlist_id, c.id, c.number, c.name, c.logo, c.group_id, c.url, c.epg_ref, c.archive_days, c.position, " +
                "f.channel_id IS NOT NULL, c.blocked FROM channel c LEFT JOIN favorite f ON f.playlist_id = c.playlist_id AND f.channel_id = c.id " +
                "WHERE c.playlist_id = ? AND c.id = ?",
            arrayOf(key.playlistId, key.channelId),
        ).use { c -> if (c.moveToFirst()) channelAt(c) else null }

    fun channelCount(playlistId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM channel WHERE playlist_id = ?", arrayOf(playlistId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Programmes for [channels] overlapping [from, to), by channel key. A channel's own playlist's EPG comes first.
     * Only when its own playlist has no EPG at all does another playlist's EPG fill in, and then only for the exact
     * same XMLTV channel id: display names are never used to match (provisional fallback rule, see design-layout.md).
     */
    fun programmes(channels: List<Channel>, from: Long, to: Long): Map<ChannelKey, List<Programme>> {
        val out = HashMap<ChannelKey, List<Programme>>()
        if (channels.isEmpty()) return out
        val withEpg = sourcesWithEpg()
        val sourceOrder = playlists().map { it.id }
        val db = readableDatabase
        for (ch in channels) {
            val ref = ch.epgRef ?: continue
            val sources = if (ch.playlistId in withEpg) listOf(ch.playlistId) else sourceOrder.filter { it in withEpg }
            for (source in sources) {
                val list = db.rawQuery(
                    "SELECT start, stop, title, description, category FROM program WHERE source = ? AND epg_ref = ? AND stop > ? AND start < ? ORDER BY start",
                    arrayOf(source, ref, from.toString(), to.toString()),
                ).use { c -> buildList { while (c.moveToNext()) add(Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) } }
                if (list.isNotEmpty()) {
                    out[ch.key] = list
                    break
                }
            }
        }
        return out
    }

    private fun sourcesWithEpg(): Set<String> =
        readableDatabase.rawQuery("SELECT source FROM epg_status WHERE programmes > 0", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    // ---- The viewer's own data ---------------------------------------------------------------------------------

    fun setFavorite(key: ChannelKey, favorite: Boolean, now: Long) {
        if (favorite) {
            val v = ContentValues().apply { put("playlist_id", key.playlistId); put("channel_id", key.channelId); put("added", now) }
            writableDatabase.insertWithOnConflict("favorite", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.delete("favorite", "playlist_id = ? AND channel_id = ?", arrayOf(key.playlistId, key.channelId))
        }
    }

    /** Notes a channel as watched; only the last [RECENT_KEEP] are kept. */
    fun noteRecent(key: ChannelKey, now: Long) {
        val db = writableDatabase
        val v = ContentValues().apply { put("playlist_id", key.playlistId); put("channel_id", key.channelId); put("at", now) }
        db.insertWithOnConflict("recent", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM recent WHERE (playlist_id || '/' || channel_id) NOT IN " +
                "(SELECT playlist_id || '/' || channel_id FROM recent ORDER BY at DESC LIMIT $RECENT_KEEP)",
        )
    }

    /** The most recently watched channels, newest first. */
    fun recent(): List<Channel> {
        val keys = readableDatabase.rawQuery("SELECT playlist_id, channel_id FROM recent ORDER BY at DESC LIMIT $RECENT_KEEP", null).use { c ->
            buildList { while (c.moveToNext()) add(ChannelKey(c.getString(0), c.getString(1))) }
        }
        return keys.mapNotNull { channel(it) }
    }

    private fun bindOrNull(s: android.database.sqlite.SQLiteStatement, i: Int, v: String?) {
        if (v == null) s.bindNull(i) else s.bindString(i, v)
    }

    private fun playlistAt(c: Cursor) = Playlist(
        id = c.getString(0), name = c.getString(1), kind = PlaylistKind.of(c.getString(2)), url = c.getString(3),
        username = c.getString(4), password = c.getString(5), epgUrl = c.getString(6), output = c.getString(7),
        includeLive = c.getInt(8) != 0, includeVod = c.getInt(9) != 0, position = c.getInt(10), updateHours = c.getInt(11),
        lastSync = c.getLong(12), lastStatus = c.getString(13), expiresAt = c.getLong(14), maxConnections = c.getInt(15),
    )

    private fun channelAt(c: Cursor) = Channel(
        playlistId = c.getString(0), id = c.getString(1), number = c.getInt(2), name = c.getString(3),
        logo = c.getString(4), groupId = c.getString(5), url = c.getString(6), epgRef = c.getString(7),
        archiveDays = c.getInt(8), position = c.getInt(9), favorite = c.getInt(10) != 0, blocked = c.getInt(11) != 0,
    )

    companion object {
        private const val VERSION = 2
        const val RECENT_KEEP = 10
        private const val PLAYLIST_COLS = "id, name, kind, url, username, password, epg_url, output, include_live, include_vod, " +
            "position, update_hours, last_sync, last_status, expires_at, max_connections"

        @Volatile private var instance: LiveDb? = null

        fun get(context: Context): LiveDb =
            instance ?: synchronized(this) { instance ?: LiveDb(context.applicationContext).also { instance = it } }
    }
}
