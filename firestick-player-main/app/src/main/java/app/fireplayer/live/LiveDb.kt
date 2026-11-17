package app.fireplayer.live

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Live TV persistence: playlists, channels, groups, and the channel → EPG reference map.
 * WAL, one writer, no rowids — the same shape as [app.fireplayer.titles.TitleBook].
 *
 * A sync refreshes the provider's columns (name, logo, group) and leaves the viewer's own columns
 * (favorite, blocked, position) alone, so favorites and manual sorting survive every refresh.
 */
class LiveDb(context: Context) : SQLiteOpenHelper(context, "live.db", null, VERSION) {

    enum class PlaylistKind(val code: String) { M3U("m3u"), XTREAM("xtream") }

    data class Playlist(val id: String, val kind: PlaylistKind, val urlOrCreds: String, val lastSync: Long)
    data class Channel(val playlistId: String, val id: String, val name: String, val logo: String?, val groupId: String?, val position: Int, val favorite: Boolean = false, val blocked: Boolean = false)
    data class ChannelGroup(val playlistId: String, val id: String, val name: String, val position: Int)
    data class ChannelEpgMap(val playlistId: String, val channelId: String, val epgRef: String?)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playlist(id TEXT PRIMARY KEY, kind TEXT NOT NULL, url_or_creds TEXT NOT NULL, last_sync INTEGER NOT NULL) WITHOUT ROWID")
        db.execSQL("CREATE TABLE channel_group(playlist_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE channel(playlist_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, logo TEXT, group_id TEXT, position INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE channel_epg_map(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, epg_ref TEXT, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First Live TV schema: nothing earlier to migrate.
    }

    /** Every playlist, for [PlaylistManager.sync]. */
    fun playlists(): List<Playlist> =
        readableDatabase.rawQuery("SELECT id, kind, url_or_creds, last_sync FROM playlist", null).use { c ->
            buildList {
                while (c.moveToNext()) add(Playlist(c.getString(0), kindOf(c.getString(1)), c.getString(2), c.getLong(3)))
            }
        }

    /** Register (or re-register) a playlist; the add-playlist flow calls this before a sync. */
    fun upsertPlaylist(p: Playlist) {
        writableDatabase.insertWithOnConflict("playlist", null, playlistValues(p), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * One playlist after a sync: its [playlist.lastSync] is recorded, then its groups, channels and
     * EPG map are upserted. Provider fields overwrite; a channel's favorite, blocked and position
     * survive the refresh.
     */
    fun applySync(playlist: Playlist, groups: List<ChannelGroup>, channels: List<Channel>, maps: List<ChannelEpgMap>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("playlist", null, playlistValues(playlist), SQLiteDatabase.CONFLICT_REPLACE)

            val refreshGroup = db.compileStatement("UPDATE channel_group SET name = ? WHERE playlist_id = ? AND id = ?")
            val addGroup = db.compileStatement("INSERT OR IGNORE INTO channel_group(playlist_id, id, name, position) VALUES (?, ?, ?, ?)")
            for (g in groups) {
                refreshGroup.clearBindings(); refreshGroup.bindString(1, g.name); refreshGroup.bindString(2, g.playlistId); refreshGroup.bindString(3, g.id); refreshGroup.executeUpdateDelete()
                addGroup.clearBindings(); addGroup.bindString(1, g.playlistId); addGroup.bindString(2, g.id); addGroup.bindString(3, g.name); addGroup.bindLong(4, g.position.toLong()); addGroup.executeInsert()
            }

            val refreshChannel = db.compileStatement("UPDATE channel SET name = ?, logo = ?, group_id = ? WHERE playlist_id = ? AND id = ?")
            val addChannel = db.compileStatement("INSERT OR IGNORE INTO channel(playlist_id, id, name, logo, group_id, position, favorite, blocked) VALUES (?, ?, ?, ?, ?, ?, 0, 0)")
            for (c in channels) {
                refreshChannel.clearBindings(); refreshChannel.bindString(1, c.name)
                c.logo?.let { refreshChannel.bindString(2, it) } ?: refreshChannel.bindNull(2)
                c.groupId?.let { refreshChannel.bindString(3, it) } ?: refreshChannel.bindNull(3)
                refreshChannel.bindString(4, c.playlistId); refreshChannel.bindString(5, c.id); refreshChannel.executeUpdateDelete()

                addChannel.clearBindings(); addChannel.bindString(1, c.playlistId); addChannel.bindString(2, c.id); addChannel.bindString(3, c.name)
                c.logo?.let { addChannel.bindString(4, it) } ?: addChannel.bindNull(4)
                c.groupId?.let { addChannel.bindString(5, it) } ?: addChannel.bindNull(5)
                addChannel.bindLong(6, c.position.toLong()); addChannel.executeInsert()
            }

            val map = db.compileStatement("INSERT OR REPLACE INTO channel_epg_map(playlist_id, channel_id, epg_ref) VALUES (?, ?, ?)")
            for (x in maps) {
                map.clearBindings(); map.bindString(1, x.playlistId); map.bindString(2, x.channelId)
                x.epgRef?.let { map.bindString(3, it) } ?: map.bindNull(3)
                map.executeInsert()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun playlistValues(p: Playlist) = ContentValues().apply {
        put("id", p.id)
        put("kind", p.kind.code)
        put("url_or_creds", p.urlOrCreds)
        put("last_sync", p.lastSync)
    }

    private fun kindOf(code: String): PlaylistKind = PlaylistKind.values().firstOrNull { it.code == code } ?: PlaylistKind.M3U

    private companion object {
        const val VERSION = 1
    }
}
