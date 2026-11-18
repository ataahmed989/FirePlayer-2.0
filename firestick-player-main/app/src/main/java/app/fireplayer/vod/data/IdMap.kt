package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Where [IdMap] keeps its answers (vod.db on the TV). Reads may come from any thread; writes come one at a time. */
interface IdStore {
    /** The IMDb id ("" when TMDB had none) and when it was learnt (ms); null when never asked. */
    fun idFor(kind: Kind, tmdbId: Int): Pair<String, Long>?

    fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>)
}

/** An [IdStore] in memory only, for tests and for a TV whose database can't be opened. */
class MemoryIdStore : IdStore {
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    override fun idFor(kind: Kind, tmdbId: Int) = map["${kind.type}|$tmdbId"]
    override fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>) {
        entries.forEach { (tmdb, imdb, at) -> map["${kind.type}|$tmdb"] = imdb to at }
    }
    val size: Int get() = map.size
}

/**
 * TMDB id → IMDb id, per kind (TMDB's film and TV ids are separate numbers). Hits are kept for good; a miss (TMDB had
 * no IMDb id) only for [MISS_KEPT_MS], since new titles often get theirs a few days later. Cinemeta's pages teach it
 * pairs for nothing ([learn]); TMDB answers fill in the rest. Reads go through a memory copy; writes go to [writer].
 */
class IdMap(private val store: IdStore, private val clock: () -> Long, private val writer: Executor) {

    private val memory = ConcurrentHashMap<String, Pair<String, Long>>()

    fun known(kind: Kind, tmdbId: Int): TmdbTrending.Known {
        val key = "${kind.type}|$tmdbId"
        val entry = memory[key] ?: runCatching { store.idFor(kind, tmdbId) }.getOrNull()?.also { memory[key] = it }
            ?: return TmdbTrending.Known.Unknown
        val (imdb, at) = entry
        if (imdb.isNotEmpty()) return TmdbTrending.Known.Hit(imdb)
        return if (clock() - at in 0 until MISS_KEPT_MS) TmdbTrending.Known.Miss else TmdbTrending.Known.Unknown
    }

    fun found(kind: Kind, tmdbId: Int, imdb: String) = put(kind, listOf(tmdbId to imdb))

    fun missed(kind: Kind, tmdbId: Int) = put(kind, listOf(tmdbId to ""))

    /** The pairs Cinemeta's titles carry (`moviedb_id`), after its junk filters. Only new or changed ones are written. */
    fun learn(titles: List<Title>) {
        for (kind in Kind.entries) {
            val pairs = titles.filter { it.kind == kind && it.tmdbId != null && it.id.startsWith("tt") }
                .map { it.tmdbId!! to it.id }
                .filter { (tmdb, imdb) -> memory["${kind.type}|$tmdb"]?.first != imdb }
            if (pairs.isNotEmpty()) put(kind, pairs)
        }
    }

    private fun put(kind: Kind, pairs: List<Pair<Int, String>>) {
        val at = clock()
        val entries = pairs.map { (tmdb, imdb) -> Triple(tmdb, imdb, at) }
        entries.forEach { (tmdb, imdb, t) -> memory["${kind.type}|$tmdb"] = imdb to t }
        writer.execute { runCatching { store.putIds(kind, entries) } }
    }

    companion object {
        const val MISS_KEPT_MS = 2 * 24 * 3600_000L
    }
}
