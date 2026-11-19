package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Position
import app.fireplayer.vod.Title
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * Where the library is kept (vod.db on the TV). A change is a row with its time, and leaving a list sets a flag rather
 * than deleting, so syncing could be added later (design-brief.md §6.2: no syncing now). Called on one thread.
 */
interface LibraryStore {
    class Saved(
        val listed: List<Listed>,
        val watched: List<Watched>,
        val progress: List<Progress>,
        val positions: List<EpisodePosition> = emptyList(),
    )

    fun load(): Saved
    fun saveListed(l: Listed)
    fun saveWatched(w: Watched)

    /** Several marks in one write (a season's episodes, "Mark watched up to here"). */
    fun saveWatchedAll(marks: List<Watched>) {
        marks.forEach { saveWatched(it) }
    }

    fun saveProgress(p: Progress)
    fun saveEpisodePosition(p: EpisodePosition)
}

/** A title in My List ([inList] true) or taken out of it, at [at] (ms). */
data class Listed(val title: Title, val at: Long, val inList: Boolean)

/**
 * A watched mark set ([watched] true) or cleared at [at]. [id] is a title's IMDb id, or an episode's "tt…:S:E" (kind
 * SERIES) for the marks of single episodes.
 */
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

/** Where playback of one episode ([id], "tt…:S:E") stopped: its card's progress bar and "12m left". */
data class EpisodePosition(val id: String, val positionMs: Long, val durationMs: Long, val at: Long)

/** A [LibraryStore] in memory only, for tests and for a TV whose database can't be opened. */
class MemoryLibraryStore : LibraryStore {
    val listed = LinkedHashMap<String, Listed>()
    val watched = LinkedHashMap<String, Watched>()
    val progress = LinkedHashMap<String, Progress>()
    val positions = LinkedHashMap<String, EpisodePosition>()
    /** How many writes of several marks ([saveWatchedAll]) were made. */
    var batches = 0
    override fun load() = LibraryStore.Saved(
        listed.values.toList(), watched.values.toList(), progress.values.toList(), positions.values.toList(),
    )
    override fun saveListed(l: Listed) { listed["${l.title.kind.type}|${l.title.id}"] = l }
    override fun saveWatched(w: Watched) { watched["${w.kind.type}|${w.id}"] = w }
    override fun saveWatchedAll(marks: List<Watched>) {
        batches++
        marks.forEach { saveWatched(it) }
    }
    override fun saveProgress(p: Progress) { progress["${p.title.kind.type}|${p.title.id}"] = p }
    override fun saveEpisodePosition(p: EpisodePosition) { positions[p.id] = p }
}

/**
 * The user's library on this TV: My List, watched marks (titles and single episodes), Continue Watching and where
 * playback of each episode stopped (written by playback, step 3). Kept in memory, so the main thread can ask at once,
 * and written to [store] on [writer] (which also reads it, first). Titles are keyed by kind and IMDb id; the
 * contract's id-only questions match either kind. [changed] is called after every change, on the calling thread.
 *
 * An episode counts as watched by the newer of its own mark and the whole show's mark; the show's covers only the
 * episodes that had aired when it was set. Times come from [clock] but never repeat or go back ([stamp]), so of two
 * marks the later one always wins.
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
    private val positions = HashMap<String, EpisodePosition>()
    private val saved = FutureTask { runCatching { store.load() }.getOrNull() }
    @Volatile private var ready = false
    private var lastStamp = Long.MIN_VALUE

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
                listOf(Listed(title, stamp(), true).also { listed[k] = it })
            } else {
                Kind.entries.mapNotNull { k -> listed[key(k, title.id)]?.takeIf { it.inList } }
                    .map { Listed(it.title, stamp(), false).also { l -> listed[key(l.title.kind, l.title.id)] = l } }
            }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveListed(it) } } }
        changed()
    }

    fun isWatched(id: String): Boolean = state { Kind.entries.any { watched[key(it, id)]?.watched == true } }

    /**
     * Marks [title] watched (it leaves Continue Watching), or its id not watched whichever kind was marked (as
     * [isWatched] asks by id). A show is marked anew each time, so its mark covers the episodes aired by then; marked
     * not watched, it clears its episodes' marks too (those aired by then).
     */
    fun setWatched(title: Title, watched: Boolean) {
        val entries = state {
            if (watched) {
                val k = key(title.kind, title.id)
                if (title.kind != Kind.SERIES && this.watched[k]?.watched == true) return
                listOf(Watched(title.kind, title.id, stamp(), true).also { this.watched[k] = it })
            } else {
                Kind.entries.filter { k ->
                    this.watched[key(k, title.id)]?.watched == true || (k == Kind.SERIES && anyEpisodeMarked(title.id))
                }.map { k -> Watched(k, title.id, stamp(), false).also { this.watched[key(k, title.id)] = it } }
            }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveWatched(it) } } }
        changed()
    }

    /** Whether [episode] counts as watched (see the class). */
    fun isEpisodeWatched(episode: Episode): Boolean = state { episodeMark(episode.id, episode.released)?.watched == true }

    /**
     * Marks [episodes] watched or not: one write, one call of [changed]; those already so are left as they are
     * (nothing to do: no write, no call).
     */
    fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean) {
        val marks = state {
            val at = stamp()
            episodes.distinctBy { it.id }
                .filter { (episodeMark(it.id, it.released)?.watched == true) != watched }
                .map { Watched(Kind.SERIES, it.id, at, watched).also { w -> this.watched[key(Kind.SERIES, it.id)] = w } }
        }
        if (marks.isEmpty()) return
        writer.execute { runCatching { store.saveWatchedAll(marks) } }
        changed()
    }

    /**
     * Continue Watching for [kind] (null: films and series), the latest played first: titles not taken out of it and
     * not marked watched since. Each card carries its progress and caption ("12m left", "S2 E5 · 12m left").
     */
    fun continueWatching(kind: Kind?): List<Card> = state {
        progress.values
            .filter { !it.hidden && (kind == null || it.title.kind == kind) && !markedSince(it) }
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

    /** Whether [id] is in Continue Watching: played, not taken out and not marked watched since (either kind). */
    fun inContinueWatching(id: String): Boolean = state {
        Kind.entries.any { k -> progress[key(k, id)]?.let { !it.hidden && !markedSince(it) } == true }
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
     * Where playback of [title] stopped (playback, step 3): it goes to the front of Continue Watching, back in it if it
     * was taken out; a series' [episode] keeps its own position too. A finished position ([Position.finished]) also
     * marks the film or the episode watched. One call of [changed]. Any thread.
     */
    fun recordProgress(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        val dur = durationMs.coerceAtLeast(0)
        val (entry, own, mark) = state {
            val at = stamp()
            val entry = Progress(title, episode, pos, dur, at).also { progress[key(title.kind, title.id)] = it }
            val own = if (title.kind == Kind.SERIES && episode != null) {
                EpisodePosition(Episode.idOf(title.id, episode.season, episode.episode), pos, dur, at)
                    .also { positions[it.id] = it }
            } else {
                null
            }
            val mark = if (Position(pos, dur, at).finished) {
                val id = own?.id ?: title.id
                Watched(title.kind, id, stamp(), true).also { watched[key(title.kind, id)] = it }
            } else {
                null
            }
            Triple(entry, own, mark)
        }
        writer.execute {
            runCatching { store.saveProgress(entry) }
            own?.let { runCatching { store.saveEpisodePosition(it) } }
            mark?.let { runCatching { store.saveWatched(it) } }
        }
        changed()
    }

    /** The resume point of [title], also when it was taken out of Continue Watching. */
    fun progressOf(kind: Kind, id: String): Progress? = state { progress[key(kind, id)] }

    /**
     * [kind]/[id]'s Continue Watching record, with whether the film (or the series' episode) was marked watched after
     * it was saved; null when never played.
     */
    fun lastPlayed(kind: Kind, id: String): LastPlayed? = state {
        val p = progress[key(kind, id)] ?: return@state null
        LastPlayed(p.episode, Position(p.positionMs, p.durationMs, p.at), p.hidden, markedSince(p))
    }

    /**
     * Where playback of [episode] stopped; for one played before positions were kept per episode, the series' record
     * when it is about that episode. Null when never played.
     */
    fun positionOf(episode: Episode): Position? = state {
        positions[episode.id]?.let { return@state Position(it.positionMs, it.durationMs, it.at) }
        val p = progress[key(Kind.SERIES, episode.showId)] ?: return@state null
        val ref = p.episode ?: return@state null
        if (ref.season == episode.season && ref.episode == episode.episode) Position(p.positionMs, p.durationMs, p.at) else null
    }

    // ---- Under the lock ----

    /** A time for a change: the clock, but always after the last one. */
    private fun stamp(): Long {
        val now = clock()
        lastStamp = if (lastStamp == Long.MIN_VALUE || now > lastStamp) now else lastStamp + 1
        return lastStamp
    }

    /**
     * The mark that decides whether an episode is watched: its own, or the show's when that is newer and the episode
     * ([released], epoch ms) had aired by then; null when neither applies.
     */
    private fun episodeMark(id: String, released: Long?): Watched? {
        val own = watched[key(Kind.SERIES, id)]
        val show = watched[key(Kind.SERIES, id.substringBefore(':'))]
            ?.takeIf { released != null && released <= it.at }
        return when {
            show == null -> own
            own == null || show.at > own.at -> show
            else -> own
        }
    }

    /**
     * The film, or the series' episode, of [p] was marked watched after [p] was saved (the show's mark counts for an
     * episode: one being played has aired).
     */
    private fun markedSince(p: Progress): Boolean {
        val title = watched[key(p.title.kind, p.title.id)]
        val ep = p.episode
        if (p.title.kind != Kind.SERIES || ep == null) return title != null && title.watched && title.at >= p.at
        val own = watched[key(Kind.SERIES, Episode.idOf(p.title.id, ep.season, ep.episode))]
        val newest = listOfNotNull(own, title).maxByOrNull { it.at } ?: return false
        return newest.watched && newest.at >= p.at
    }

    /** Some episode of [showId] has its own watched mark set. */
    private fun anyEpisodeMarked(showId: String): Boolean {
        val prefix = "${Kind.SERIES.type}|$showId:"
        return watched.any { (k, w) -> w.watched && k.startsWith(prefix) }
    }

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
                // What changed before the store was read wins over what it holds. (getOrPut, not putIfAbsent: that
                // is API 24, and the app runs from API 22.)
                s.listed.forEach { l -> listed.getOrPut(key(l.title.kind, l.title.id)) { l } }
                s.watched.forEach { w -> watched.getOrPut(key(w.kind, w.id)) { w } }
                s.progress.forEach { p -> progress.getOrPut(key(p.title.kind, p.title.id)) { p } }
                s.positions.forEach { p -> positions.getOrPut(p.id) { p } }
                // Stamps stay after anything stored (a clock set back since).
                val stored = (s.listed.map { it.at } + s.watched.map { it.at } + s.progress.map { it.at } + s.positions.map { it.at })
                    .maxOrNull()
                if (stored != null && stored > lastStamp) lastStamp = stored
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
