package app.fireplayer.vod.data

import android.util.Log
import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.HeaderFeed
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Page
import app.fireplayer.vod.Position
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.hasImdbId
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * Films & Shows' data: rows from Cinemeta, Top 10 from TMDB's trending lists with the user's key, and the library kept
 * on this TV ([VodLibrary]). Answers come from the cache on disk at once and are refreshed in the background (Cinemeta
 * pages about 4 h, the manifest and metas a day, Top 10 6 h).
 *
 * Threads: [io] (one thread) reads the cache and the database and writes the database; [net] fetches; [lookups] runs
 * TMDB's id lookups and Cinemeta metas for a Top 10 side by side; every callback goes through [main] and is dropped once
 * its [Cancel] is called. The same page or list asked for by several rows at once is fetched once.
 *
 * Privacy: no address is logged (TMDB's carries the key), only what kind of failure it was.
 */
class DefaultVodRepository(
    private val http: Http,
    private val tmdb: TmdbSource,
    private val tmdbKey: () -> String,
    private val cache: JsonCache,
    idStore: IdStore,
    libraryStore: LibraryStore,
    private val main: Executor,
    private val io: Executor,
    private val net: Executor,
    private val lookups: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) : VodRepository {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The library; also for later steps (playback records where it stopped with [VodLibrary.recordProgress]). */
    val library = VodLibrary(libraryStore, clock, io) { main.execute { listeners.forEach { it() } } }

    private val ids = IdMap(idStore, clock, io)
    private val pages = Lru<String, Stored<Cinemeta.Page>>(PAGES_KEPT)
    private val byId = Lru<String, Title>(TITLES_KEPT)
    private val byTmdb = Lru<String, Title>(TITLES_KEPT)
    private val top10 = ConcurrentHashMap<Kind, Stored<List<Card>>>()
    private val running = ConcurrentHashMap<String, FutureTask<*>>()
    @Volatile private var manifest: Stored<Cinemeta.Manifest>? = null
    @Volatile private var refusedKey: String? = null
    @Volatile private var tmdbPausedUntil = 0L

    init {
        io.execute {
            cache.read(REFUSED)?.takeIf { it.isFresh(clock()) }?.let { refusedKey = it.data.optString("key").ifEmpty { null } }
            storedManifest()
            cache.trim()
        }
    }

    // ---- Rows ----

    override fun rows(page: Page): List<RowSpec> {
        val m = manifest
        if (m == null || !m.isFresh(clock())) refreshManifest()
        return PageRows.rows(page, newYear(if (page == Page.SHOWS) Kind.SERIES else Kind.MOVIE))
    }

    override fun load(row: RowSpec, skip: Int, limit: Int, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        val sink: (List<Card>, Boolean) -> Unit = { cards, fresh -> post(req) { onCards(cards, fresh) } }
        when (val s = row.source) {
            is Source.Catalog -> catalogCards(resolve(s), skip, limit, req) { titles, fresh -> sink(titles.map { Card(it) }, fresh) }
            is Source.Trending -> trendingCards(s.kind, skip, limit, req, sink)
            is Source.ContinueWatching -> local(req, skip, limit, sink) { library.continueWatching(s.kind) }
            Source.MyList -> local(req, skip, limit, sink) { library.myList().map { Card(it) } }
            // Filled by playback in a later step.
            is Source.NextEpisodes -> local(req, skip, limit, sink) { emptyList() }
            // Chips come from genres().
            is Source.Genres -> local(req, skip, limit, sink) { emptyList() }
            // The title page fills these rows itself (details(), moreLikeThis()).
            is Source.Fixed -> local(req, skip, limit, sink) { emptyList() }
        }
        return req
    }

    override fun genres(kind: Kind, onGenres: (List<String>) -> Unit): Cancel {
        val req = Req()
        io.execute {
            if (req.cancelled) return@execute
            val stored = storedManifest()
            val first = Cinemeta.genres(stored?.value, kind)
            post(req) { onGenres(first) }
            if (stored != null && stored.isFresh(clock())) return@execute
            net.execute {
                if (req.cancelled) return@execute
                val list = Cinemeta.genres(fetchManifest()?.value ?: return@execute, kind)
                if (list != first) post(req) { onGenres(list) }
            }
        }
        return req
    }

    override fun header(page: Page, onFeed: (HeaderFeed) -> Unit): Cancel {
        val req = Req()
        val kinds = when (page) {
            Page.HOME -> listOf(Kind.MOVIE, Kind.SERIES)
            Page.MOVIES -> listOf(Kind.MOVIE)
            Page.SHOWS -> listOf(Kind.SERIES)
        }
        val mix = HeaderMix(kinds) { feed -> post(req) { onFeed(feed) } }
        for (kind in kinds) headerTitles(kind, req) { titles, fresh -> mix.update(kind, titles, fresh) }
        return req
    }

    override fun hasTmdbKey(): Boolean = usableKey()

    // ---- Library ----

    override fun inMyList(id: String) = library.inMyList(id)
    override fun setInMyList(title: Title, inList: Boolean) = library.setInMyList(title, inList)
    override fun isWatched(id: String) = library.isWatched(id)
    override fun setWatched(title: Title, watched: Boolean) = library.setWatched(title, watched)
    override fun removeFromContinueWatching(id: String) = library.removeFromContinueWatching(id)

    override fun addLibraryListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeLibraryListener(listener: () -> Unit) {
        listeners -= listener
    }

    // ---- Title page (step 2) ----
    // STUB FOR THE DATA ENGINEER (step 2): compiles and answers safely so the page can be built against it. details()
    // gives only what the title already says (no meta, no TMDB, no episodes), moreLikeThis() nothing, resolve() only
    // titles that already have their IMDb id; episode marks and positions aren't kept yet. Replace each with the real
    // work (Cinemeta meta, TMDB details, More Like This, the per-episode library), following the contract's KDoc in
    // VodContract.kt.

    override fun details(title: Title, onDetails: (details: TitleDetails, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        post(req) { onDetails(TitleDetails.of(title), true) } // STUB (data engineer)
        return req
    }

    override fun moreLikeThis(title: Title, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        post(req) { onCards(emptyList(), true) } // STUB (data engineer)
        return req
    }

    override fun resolve(title: Title, onTitle: (Title?) -> Unit): Cancel {
        val req = Req()
        post(req) { onTitle(title.takeIf { it.hasImdbId }) } // STUB (data engineer): no TMDB lookup yet
        return req
    }

    override fun isEpisodeWatched(episode: Episode): Boolean = false // STUB (data engineer)

    override fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean) = Unit // STUB (data engineer)

    override fun markWatchedUpTo(details: TitleDetails, upTo: Episode) = Unit // STUB (data engineer)

    override fun inContinueWatching(id: String): Boolean = library.continueWatching(null).any { it.title.id == id }

    // STUB (data engineer): markedWatched isn't worked out yet.
    override fun lastPlayed(title: Title): LastPlayed? = library.progressOf(title.kind, title.id)?.let {
        LastPlayed(it.episode, Position(it.positionMs, it.durationMs, it.at), it.hidden)
    }

    override fun positionOf(episode: Episode): Position? = null // STUB (data engineer)

    // STUB (data engineer): only the title's record; no episode position, no watched mark at the end.
    override fun recordProgress(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long) =
        library.recordProgress(title, episode, positionMs, durationMs)

    // ---- Cinemeta catalogs ----

    /** A catalog with its genre settled ("New" always asks for a year). */
    private data class Query(val kind: Kind, val catalog: String, val genre: String?) {
        fun url(base: Int) = Cinemeta.catalogUrl(kind, catalog, genre, base)
    }

    private fun resolve(s: Source.Catalog) =
        Query(s.kind, s.catalog, if (s.catalog == Cinemeta.YEAR) s.genre ?: newYear(s.kind).toString() else s.genre)

    /**
     * Titles at [skip] until [skip] + [limit] of [q], to [sink] on a background thread: stored pages at once (fresh
     * when they all still are), then the fetched ones. A page that can't be fetched keeps its stored copy.
     */
    private fun catalogCards(q: Query, skip: Int, limit: Int, req: Req, sink: (List<Title>, Boolean) -> Unit) {
        val bases = Paging.bases(skip, limit)
        io.execute {
            if (req.cancelled) return@execute
            val stored = bases.map { storedPage(q.url(it)) }
            val cut = { pages: List<Cinemeta.Page?> -> Paging.slice(bases, pages, skip, limit) }
            val now = clock()
            if (Paging.allFresh(stored, { it.isFresh(now) }, { !it.value.hasMore })) {
                sink(cut(stored.map { it?.value }), true)
                return@execute
            }
            if (stored.any { it != null }) sink(cut(stored.map { it?.value }), false)
            net.execute {
                if (req.cancelled) return@execute
                val got = ArrayList<Cinemeta.Page?>()
                var ended = false
                for ((i, base) in bases.withIndex()) {
                    val old = stored[i]
                    val page = when {
                        ended -> null
                        old != null && old.isFresh(clock()) -> old.value
                        else -> fetchPage(q, base)?.value ?: old?.value
                    }
                    got += page
                    if (page != null && !page.hasMore) ended = true
                }
                sink(cut(got), true)
            }
        }
    }

    /** The page as stored in memory or on disk (its titles indexed); null when never fetched. */
    private fun storedPage(url: String): Stored<Cinemeta.Page>? {
        pages[url]?.let { return it }
        val entry = cache.read(url) ?: return null
        val page = Cinemeta.Page(
            TitleJson.readTitles(entry.data.optJSONArray("titles")),
            entry.data.optBoolean("hasMore", true),
            entry.freshMs,
        )
        return Stored(page, entry.at, entry.freshMs).also {
            pages[url] = it
            index(page.titles)
        }
    }

    /** Fetches a page (once, however many ask meanwhile), stores it trimmed and learns its ids. Null on failure. */
    private fun fetchPage(q: Query, base: Int): Stored<Cinemeta.Page>? {
        val url = q.url(base)
        return single(url) {
            try {
                val page = Cinemeta.parseCatalog(http.get(url), q.kind, q.catalog, q.genre, currentYear())
                val now = clock()
                cache.write(url, JSONObject().put("titles", TitleJson.writeTitles(page.titles)).put("hasMore", page.hasMore), page.maxAgeMs, now)
                index(page.titles)
                ids.learn(page.titles)
                Stored(page, now, page.maxAgeMs).also { pages[url] = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Cinemeta catalog not read: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    private fun index(titles: List<Title>) {
        for (t in titles) {
            byId["${t.kind.type}|${t.id}"] = t
            t.tmdbId?.let { byTmdb["${t.kind.type}#$it"] = t }
        }
    }

    // ---- Manifest ----

    private fun storedManifest(): Stored<Cinemeta.Manifest>? {
        manifest?.let { return it }
        val entry = cache.read(Cinemeta.manifestUrl()) ?: return null
        return Stored(readManifest(entry.data), entry.at, entry.freshMs).also { manifest = it }
    }

    private fun refreshManifest() {
        io.execute {
            val stored = storedManifest()
            if (stored == null || !stored.isFresh(clock())) net.execute { fetchManifest() }
        }
    }

    private fun fetchManifest(): Stored<Cinemeta.Manifest>? = single("manifest") {
        try {
            val m = Cinemeta.parseManifest(http.get(Cinemeta.manifestUrl()))
            val now = clock()
            cache.write(Cinemeta.manifestUrl(), writeManifest(m), Cinemeta.MANIFEST_FRESH_MS, now)
            Stored(m, now, Cinemeta.MANIFEST_FRESH_MS).also { manifest = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta manifest not read: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun newYear(kind: Kind) = Cinemeta.newYear(manifest?.value?.years?.get(kind), currentYear())

    private fun currentYear() = Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.YEAR)

    // ---- Top 10 (TMDB) ----

    /** A Top 10 slice to [sink]; empty without a usable key. Stored list at once, then the refreshed one. */
    private fun trendingCards(kind: Kind, skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        io.execute {
            if (req.cancelled) return@execute
            if (!usableKey()) {
                sink(emptyList(), true)
                return@execute
            }
            val stored = storedTop10(kind)
            if (stored != null && stored.isFresh(clock())) {
                sink(stored.value.window(skip, limit), true)
                return@execute
            }
            if (stored != null) sink(stored.value.window(skip, limit), false)
            net.execute {
                if (req.cancelled) return@execute
                val fresh = refreshTop10(kind)
                sink((fresh ?: stored?.value.orEmpty()).window(skip, limit), true)
            }
        }
    }

    private fun storedTop10(kind: Kind): Stored<List<Card>>? {
        top10[kind]?.let { return it }
        val entry = cache.read(top10Key(kind)) ?: return null
        val cards = TitleJson.readCards(entry.data.optJSONArray("cards"))
        return Stored(cards, entry.at, entry.freshMs).also { top10[kind] = it }
    }

    /** A new Top 10 (once, however many ask meanwhile); empty when TMDB refuses the key, null to keep the stored one. */
    private fun refreshTop10(kind: Kind): List<Card>? = single("top10|${kind.type}") { buildTop10(kind) }

    private fun buildTop10(kind: Kind): List<Card>? {
        if (!usableKey()) return emptyList()
        if (clock() < tmdbPausedUntil) return null
        // Cinemeta's Popular page carries the TMDB id of each of its titles: most of the Top 10 costs no lookup.
        val popular = Query(kind, Cinemeta.TOP, null)
        if (storedPage(popular.url(0)) == null) fetchPage(popular, 0)

        val candidates = try {
            TmdbTrending.parse(tmdb.trending(kind), kind)
        } catch (t: Throwable) {
            return tmdbFailed(t)
        }
        val known = { c: TmdbTrending.Candidate ->
            byTmdb["${kind.type}#${c.tmdbId}"]?.let { TmdbTrending.Known.Hit(it.id) } ?: ids.known(kind, c.tmdbId)
        }
        var partial = false
        while (true) {
            val ask = TmdbTrending.toAsk(candidates, known)
            if (ask.isEmpty()) break
            val failure = lookUp(kind, ask) ?: continue
            if (failure is Tmdb.HttpError && (failure.code == 401 || failure.code == 403)) return tmdbFailed(failure)
            if (failure is Tmdb.HttpError && failure.code == 429) tmdbFailed(failure)
            else Log.w(TAG, "TMDB ids not read: ${failure.javaClass.simpleName}")
            // What's known makes this Top 10 for now; the rest is asked again in a few minutes.
            partial = true
            break
        }
        val ranked = TmdbTrending.ranked(candidates, known)
        val metas = ranked.filter { (_, imdb) -> byId["${kind.type}|$imdb"] == null }
            .associate { (_, imdb) -> imdb to FutureTask { metaTitle(kind, imdb) }.also(lookups::execute) }
        val cards = ranked.mapIndexed { i, (c, imdb) ->
            val cinemeta = byId["${kind.type}|$imdb"] ?: metas[imdb]?.let { runCatching { it.get() }.getOrNull() }
            Card(TmdbTrending.title(c, imdb, cinemeta), rank = i + 1)
        }
        val now = clock()
        val freshMs = if (partial || cards.isEmpty()) RETRY_MS else TmdbTrending.FRESH_MS
        cache.write(top10Key(kind), JSONObject().put("cards", TitleJson.writeCards(cards)), freshMs, now)
        top10[kind] = Stored(cards, now, freshMs)
        return cards
    }

    /** Asks TMDB for the IMDb ids of [ask] side by side and keeps the answers; the first failure, if any. */
    private fun lookUp(kind: Kind, ask: List<TmdbTrending.Candidate>): Throwable? {
        val tasks = ask.map { c -> c to FutureTask { tmdb.externalIds(kind, c.tmdbId)?.let(TmdbTrending::imdbOf) }.also(lookups::execute) }
        var failure: Throwable? = null
        for ((c, task) in tasks) {
            try {
                val imdb = task.get()
                if (imdb != null) ids.found(kind, c.tmdbId, imdb) else ids.missed(kind, c.tmdbId)
            } catch (e: ExecutionException) {
                if (failure == null) failure = e.cause ?: e
            } catch (e: InterruptedException) {
                if (failure == null) failure = e
            }
        }
        return failure
    }

    /** A Cinemeta meta for a Top 10 title not on its pages (kept a day): rating, genres, runtime, a series' years. */
    private fun metaTitle(kind: Kind, id: String): Title? {
        val url = Cinemeta.metaUrl(kind, id)
        cache.read(url)?.takeIf { it.isFresh(clock()) }?.let { return TitleJson.read(it.data.optJSONObject("title")) }
        return try {
            val title = Cinemeta.parseMeta(http.get(url), kind)?.takeIf { it.id == id } ?: return null
            cache.write(url, JSONObject().put("title", TitleJson.write(title)), Cinemeta.META_FRESH_MS)
            title.also { index(listOf(it)) }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta meta not read: ${t.javaClass.simpleName}")
            null
        }
    }

    /** What a TMDB failure means: a refused key is remembered (no Top 10 until it changes), 429 pauses TMDB. */
    private fun tmdbFailed(t: Throwable): List<Card>? {
        val code = (t as? Tmdb.HttpError)?.code
        Log.w(TAG, "TMDB trending not read: ${code ?: t.javaClass.simpleName}")
        when (code) {
            401, 403 -> {
                val hash = keyHash(tmdbKey())
                refusedKey = hash
                cache.write(REFUSED, JSONObject().put("key", hash), REFUSED_KEPT_MS)
                return emptyList()
            }
            429 -> tmdbPausedUntil = clock() + RETRY_MS
        }
        return null
    }

    /** A key is set and TMDB hasn't refused it (a refused key counts as none until it changes, or for a day). */
    private fun usableKey(): Boolean {
        val key = tmdbKey()
        return key.isNotEmpty() && keyHash(key) != refusedKey
    }

    // ---- Header ----

    /** A kind's titles for the header: its Top 10, else (no key, or none) Cinemeta Popular's first 10. */
    private fun headerTitles(kind: Kind, req: Req, sink: (List<Title>, Boolean) -> Unit) {
        trendingCards(kind, 0, TmdbTrending.WANTED, req) { cards, fresh ->
            when {
                cards.isNotEmpty() -> sink(cards.map { it.title }, fresh)
                fresh -> catalogCards(Query(kind, Cinemeta.TOP, null), 0, TmdbTrending.WANTED, req, sink)
            }
        }
    }

    /**
     * Puts a page's header feed together from its kinds' titles (Home: films and series in turn) and passes it on
     * when it changes, once every kind has answered. An empty feed only once every kind has its final answer.
     */
    internal class HeaderMix(private val kinds: List<Kind>, private val emit: (HeaderFeed) -> Unit) {
        private val titles = HashMap<Kind, List<Title>>()
        private val settled = HashSet<Kind>()
        private var sent: List<Title>? = null

        @Synchronized
        fun update(kind: Kind, list: List<Title>, fresh: Boolean) {
            titles[kind] = list
            if (fresh) settled += kind
            if (!kinds.all { it in titles }) return
            val feed = if (kinds.size == 1) {
                titles.getValue(kinds[0]).take(TmdbTrending.WANTED)
            } else {
                TmdbTrending.interleave(titles[Kind.MOVIE].orEmpty(), titles[Kind.SERIES].orEmpty())
            }
            if (feed.isEmpty() && !kinds.all { it in settled }) return
            if (feed == sent) return
            sent = feed
            emit(HeaderFeed(feed))
        }
    }

    // ---- Plumbing ----

    private fun local(req: Req, skip: Int, limit: Int, sink: (List<Card>, Boolean) -> Unit, cards: () -> List<Card>) {
        io.execute { if (!req.cancelled) sink(cards().window(skip, limit), true) }
    }

    private fun post(req: Req, action: () -> Unit) = main.execute { if (!req.cancelled) action() }

    /** Runs [work] once for [key] however many threads ask at the same time; the others wait for its answer. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> single(key: String, work: () -> T): T {
        val mine = FutureTask<T> { work() }
        val other = running.putIfAbsent(key, mine) as FutureTask<T>?
        if (other != null) return other.get()
        try {
            mine.run()
        } finally {
            running.remove(key, mine)
        }
        return mine.get()
    }

    private class Req : Cancel {
        @Volatile var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    /** A value and how long it stays fresh from [at]. */
    private class Stored<T>(val value: T, val at: Long, val freshMs: Long) {
        fun isFresh(now: Long) = now >= at && now - at < freshMs
    }

    /** A small least-recently-used map, safe from any thread. */
    private class Lru<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }

        @Synchronized operator fun get(key: K): V? = map[key]

        @Synchronized operator fun set(key: K, value: V) {
            map[key] = value
        }
    }

    private fun <T> List<T>.window(skip: Int, limit: Int) = drop(skip.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    companion object {
        private const val TAG = "FirePlayerVod"
        private const val PAGES_KEPT = 24
        private const val TITLES_KEPT = 1500
        /** A Top 10 made while TMDB failed part-way, or a pause after "too many requests": asked again after this. */
        const val RETRY_MS = 10 * 60_000L
        const val REFUSED_KEPT_MS = 24 * 3600_000L
        private const val REFUSED = "tmdb|refused"

        private fun top10Key(kind: Kind) = "tmdb|trending|${kind.type}|week"

        private fun writeManifest(m: Cinemeta.Manifest) = JSONObject().apply {
            put("genres", JSONObject().apply { m.genres.forEach { (k, v) -> put(k.type, JSONArray(v)) } })
            put("years", JSONObject().apply { m.years.forEach { (k, v) -> put(k.type, JSONArray(v)) } })
        }

        private fun readManifest(o: JSONObject): Cinemeta.Manifest {
            fun lists(name: String) = Kind.entries.associateWith { o.optJSONObject(name)?.optJSONArray(it.type).texts() }
                .filterValues { it.isNotEmpty() }
            return Cinemeta.Manifest(lists("genres"), lists("years"))
        }

        /** Enough of a key's SHA-256 to tell keys apart; the key itself is never stored here. */
        internal fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
