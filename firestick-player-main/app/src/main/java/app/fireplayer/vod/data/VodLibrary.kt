package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * Where the library is kept (vod.db on the TV). A change is a row with its time, and leaving a list sets a flag rather
 * than deleting, so syncing could be added later (design-brief.md §6.2: no syncing now). Called on one thread.
 */
interface LibraryStore {
    class Saved(val listed: List<Listed>, val watched: List<Watched>, val progress: List<Progress>)

    fun load(): Saved
    fun saveListed(l: Listed)
    fun saveWatched(w: Watched)
    fun saveProgress(p: Progress)
}

/** A title in My List ([inList] true) or taken out of it, at [at] (ms). */
data class Listed(val title: Title, val at: Long, val inList: Boolean)

/** A watched mark set ([watched] true) or cleared at [at]. */
data class Watched(val kind: Kind, val id: String, val at: Long, val watched: Boolean)

/**
 * Where playback of a title stopped: the episode for a series, the position and length in ms, when ([at]), and
 * whether it was taken out of Continue Watching ([hidden]; the resume point stays).
 */
data class Progress(
    val title: Title,
    val episode: EpisodeRef?,
    val positionMs: Long,
    val durationMs: Long,
    val at: Long,
    val hidden: Boolean = false,
)

/** A [LibraryStore] in memory only, for tests and for a TV whose database can't be opened. */
class MemoryLibraryStore : LibraryStore {
    val listed = LinkedHashMap<String, Listed>()
    val watched = LinkedHashMap<String, Watched>()
    val progress = LinkedHashMap<String, Progress>()
    override fun load() = LibraryStore.Saved(listed.values.toList(), watched.values.toList(), progress.values.toList())
    override fun saveListed(l: Listed) { listed["${l.title.kind.type}|${l.title.id}"] = l }
    override fun saveWatched(w: Watched) { watched["${w.kind.type}|${w.id}"] = w }
    override fun saveProgress(p: Progress) { progress["${p.title.kind.type}|${p.title.id}"] = p }
}

/**
 * The user's library on this TV: My List, watched marks and Continue Watching (filled by playback in a later step).
 * Kept in memory, so the main thread can ask at once, and written to [store] on [writer] (which also reads it, first).
 * Titles are keyed by kind and IMDb id; the contract's id-only questions match either kind. [changed] is called after
 * every change, on the calling thread.
 */
class VodLibrary(
    private val store: LibraryStore,
    private val clock: () -> Long,
    private val writer: Executor,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private val listed = LinkedHashMap<String, Listed>()
    private val watched = HashMap<String, Watched>()
    private val progress = HashMap<String, Progress>()
    private val saved = FutureTask { runCatching { store.load() }.getOrNull() }
    @Volatile private var ready = false

    init {
        writer.execute(saved)
    }

    fun inMyList(id: String): Boolean = state { Kind.entries.any { listed[key(it, id)]?.inList == true } }

    /** My List, the latest added first. */
    fun myList(): List<Title> = state { listed.values.filter { it.inList }.sortedByDescending { it.at }.map { it.title } }

    /**
     * Adds [title] to My List, or takes its id out whichever kind it was added as (the pages ask [inMyList] by id, so a
     * film and a series sharing an id are both "in the list" and both leave it).
     */
    fun setInMyList(title: Title, inList: Boolean) {
        val entries = state {
            if (inList) {
                val k = key(title.kind, title.id)
                if (listed[k]?.inList == true) return
                listOf(Listed(title, clock(), true).also { listed[k] = it })
            } else {
                Kind.entries.mapNotNull { k -> listed[key(k, title.id)]?.takeIf { it.inList } }
                    .map { Listed(it.title, clock(), false).also { l -> listed[key(l.title.kind, l.title.id)] = l } }
            }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveListed(it) } } }
        changed()
    }

    fun isWatched(id: String): Boolean = state { Kind.entries.any { watched[key(it, id)]?.watched == true } }

    /**
     * Marks [title] watched (it leaves Continue Watching), or its id not watched whichever kind was marked (as
     * [isWatched] asks by id).
     */
    fun setWatched(title: Title, watched: Boolean) {
        val entries = state {
            if (watched) {
                val k = key(title.kind, title.id)
                if (this.watched[k]?.watched == true) return
                listOf(Watched(title.kind, title.id, clock(), true).also { this.watched[k] = it })
            } else {
                Kind.entries.filter { k -> this.watched[key(k, title.id)]?.watched == true }
                    .map { k -> Watched(k, title.id, clock(), false).also { this.watched[key(k, title.id)] = it } }
            }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveWatched(it) } } }
        changed()
    }

    /**
     * Continue Watching for [kind] (null: films and series), the latest played first: titles not taken out of it and
     * not marked watched since. Each card carries its progress and caption ("12m left", "S2 E5 · 12m left").
     */
    fun continueWatching(kind: Kind?): List<Card> = state {
        progress.values
            .filter { !it.hidden && (kind == null || it.title.kind == kind) }
            .filter { p -> watched[key(p.title.kind, p.title.id)]?.let { it.watched && it.at >= p.at } != true }
            .sortedByDescending { it.at }
            .map { p ->
                Card(
                    title = p.title,
                    progress = Captions.fraction(p.positionMs, p.durationMs),
                    caption = Captions.caption(p.episode, p.positionMs, p.durationMs),
                    episode = p.episode,
                )
            }
    }

    /** Takes [id] out of Continue Watching; its resume point stays (and it comes back when played again). */
    fun removeFromContinueWatching(id: String) {
        val entries = state {
            Kind.entries.mapNotNull { k -> progress[key(k, id)]?.takeIf { !it.hidden } }
                .map { it.copy(hidden = true).also { p -> progress[key(p.title.kind, p.title.id)] = p } }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveProgress(it) } } }
        changed()
    }

    /**
     * Where playback of [title] stopped (for the playback step): it goes to the front of Continue Watching, back in it
     * if it was taken out. Any thread.
     */
    fun recordProgress(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long) {
        val entry = Progress(title, episode, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), clock())
        state { progress[key(title.kind, title.id)] = entry }
        writer.execute { runCatching { store.saveProgress(entry) } }
        changed()
    }

    /** The resume point of [title], also when it was taken out of Continue Watching. */
    fun progressOf(kind: Kind, id: String): Progress? = state { progress[key(kind, id)] }

    /** Runs [block] on the library, read from the store first (it waits for that the first time only). */
    private inline fun <T> state(block: () -> T): T {
        if (!ready) merge()
        return synchronized(lock) { block() }
    }

    private fun merge() {
        val loaded = runCatching { saved.get() }.getOrNull()
        synchronized(lock) {
            if (ready) return
            loaded?.let { s ->
                // What changed before the store was read wins over what it holds.
                s.listed.forEach { l -> listed.putIfAbsent(key(l.title.kind, l.title.id), l) }
                s.watched.forEach { w -> watched.putIfAbsent(key(w.kind, w.id), w) }
                s.progress.forEach { p -> progress.putIfAbsent(key(p.title.kind, p.title.id), p) }
            }
            ready = true
        }
    }

    private fun key(kind: Kind, id: String) = "${kind.type}|$id"
}

/** The words and numbers on Continue Watching cards. */
object Captions {

    /** How far along, 0..1; null when the length isn't known. */
    fun fraction(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    /** "12m left", "1h 10m left", "1h left"; with an episode "S2 E5 · 12m left". Null for a film of unknown length. */
    fun caption(episode: EpisodeRef?, positionMs: Long, durationMs: Long): String? {
        val left = if (durationMs > 0) left(durationMs - positionMs) else null
        val ep = episode?.let { "S${it.season} E${it.episode}" }
        return listOfNotNull(ep, left).joinToString(" · ").ifEmpty { null }
    }

    /** What's left, rounded to the minute (at least 1 m). */
    fun left(remainingMs: Long): String {
        val minutes = ((remainingMs.coerceAtLeast(0) + 30_000) / 60_000).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m left"
            m == 0L -> "${h}h left"
            else -> "${h}h ${m}m left"
        }
    }
}
