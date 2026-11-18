package app.fireplayer.vod

/**
 * An in-memory [VodRepository] for the pages' tests: catalogs are lists set by the test, answered on the calling
 * thread (as the real one answers on the main thread). With [cachedFirst] a load answers twice, as the real one does
 * when its cache is old: first [cached] (fresh = false), then the catalog (fresh = true). Records every load.
 */
class FakeVodRepository : VodRepository {

    /** Each source's whole list. */
    val catalogs = HashMap<Source, List<Card>>()
    /** What a load answers first (fresh = false) when [cachedFirst]. */
    val cached = HashMap<Source, List<Card>>()
    var cachedFirst = false
    /** Loads fail (as with no network and nothing cached): one empty fresh answer. */
    var offline = false
    var tmdbKey = true
    val genres = HashMap<Kind, List<String>>()
    val header = HashMap<Page, List<Title>>()

    data class Load(val row: RowSpec, val skip: Int, val limit: Int)

    val loads = ArrayList<Load>()
    /** Loads not yet answered when [manual]: answer with [answer]. */
    var manual = false
    private val waiting = ArrayList<Triple<Load, (List<Card>, Boolean) -> Unit, BooleanArray>>()

    private val myList = LinkedHashMap<String, Title>()
    private val watched = HashSet<String>()
    private val continueRemoved = HashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    override fun rows(page: Page): List<RowSpec> = when (page) {
        Page.HOME -> listOf(
            RowSpec("home.continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true),
            RowSpec("home.mylist", "My List", RowStyle.POSTER, Source.MyList),
            RowSpec("home.next", "Next Episodes", RowStyle.WIDE, Source.NextEpisodes()),
            RowSpec("home.movies", "Popular Movies", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, "top")),
            RowSpec("home.shows", "Popular Shows", RowStyle.POSTER, Source.Catalog(Kind.SERIES, "top")),
        )
        Page.MOVIES -> listOf(
            RowSpec("movies.continue", "Continue Watching · Films", RowStyle.WIDE, Source.ContinueWatching(Kind.MOVIE), continueRow = true),
            RowSpec("movies.top10", "Top 10 This Week", RowStyle.RANKED, Source.Trending(Kind.MOVIE)),
            RowSpec("movies.popular", "Popular", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, "top")),
            RowSpec("movies.new", "New in 2026", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, "year", "2026")),
            RowSpec("movies.featured", "Featured", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, "imdbRating")),
            RowSpec("movies.genres", "Genres", RowStyle.GENRES, Source.Genres(Kind.MOVIE)),
        )
        Page.SHOWS -> listOf(
            RowSpec("shows.continue", "Continue Watching · Series", RowStyle.WIDE, Source.ContinueWatching(Kind.SERIES), continueRow = true),
            RowSpec("shows.next", "Next Episodes", RowStyle.WIDE, Source.NextEpisodes()),
            RowSpec("shows.top10", "Top 10 This Week", RowStyle.RANKED, Source.Trending(Kind.SERIES)),
            RowSpec("shows.popular", "Popular", RowStyle.POSTER, Source.Catalog(Kind.SERIES, "top")),
            RowSpec("shows.new", "New in 2026", RowStyle.POSTER, Source.Catalog(Kind.SERIES, "year", "2026")),
            RowSpec("shows.featured", "Featured", RowStyle.POSTER, Source.Catalog(Kind.SERIES, "imdbRating")),
            RowSpec("shows.genres", "Genres", RowStyle.GENRES, Source.Genres(Kind.SERIES)),
        )
    }

    override fun load(row: RowSpec, skip: Int, limit: Int, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel {
        val load = Load(row, skip, limit)
        loads += load
        val cancelled = booleanArrayOf(false)
        if (manual) {
            waiting += Triple(load, onCards, cancelled)
        } else {
            answer(load, onCards, cancelled)
        }
        return Cancel { cancelled[0] = true }
    }

    /** Answers the loads waiting (with [manual]). */
    fun answerAll() {
        val now = ArrayList(waiting)
        waiting.clear()
        for ((load, cb, cancelled) in now) answer(load, cb, cancelled)
    }

    private fun answer(load: Load, onCards: (List<Card>, Boolean) -> Unit, cancelled: BooleanArray) {
        if (cancelled[0]) return
        if (offline) {
            onCards(emptyList(), true)
            return
        }
        val all = listFor(load.row.source)
        if (cachedFirst) {
            val c = cached[load.row.source].orEmpty().drop(load.skip).take(load.limit)
            onCards(c, false)
            if (cancelled[0]) return
        }
        onCards(all.drop(load.skip).take(load.limit), true)
    }

    private fun listFor(source: Source): List<Card> = when (source) {
        is Source.MyList -> myList.values.reversed().map { Card(it) }
        is Source.ContinueWatching -> catalogs[source].orEmpty().filter { it.title.id !in continueRemoved }
        else -> catalogs[source].orEmpty()
    }

    override fun genres(kind: Kind, onGenres: (List<String>) -> Unit): Cancel {
        onGenres(genres[kind].orEmpty())
        return Cancel { }
    }

    override fun header(page: Page, onFeed: (HeaderFeed) -> Unit): Cancel {
        onFeed(HeaderFeed(header[page].orEmpty()))
        return Cancel { }
    }

    override fun hasTmdbKey(): Boolean = tmdbKey

    override fun inMyList(id: String): Boolean = id in myList

    override fun setInMyList(title: Title, inList: Boolean) {
        if (inList) myList[title.id] = title else myList.remove(title.id)
        changed()
    }

    override fun isWatched(id: String): Boolean = id in watched

    override fun setWatched(title: Title, watched: Boolean) {
        if (watched) this.watched += title.id else this.watched -= title.id
        changed()
    }

    override fun removeFromContinueWatching(id: String) {
        continueRemoved += id
        changed()
    }

    override fun addLibraryListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeLibraryListener(listener: () -> Unit) {
        listeners -= listener
    }

    val listenerCount: Int get() = listeners.size

    private fun changed() {
        for (l in ArrayList(listeners)) l()
    }

    companion object {
        /** [n] film cards "tt<from>"…, named "Film <i>". */
        fun films(n: Int, from: Int = 1000, kind: Kind = Kind.MOVIE): List<Card> =
            (0 until n).map { i -> Card(Title("tt${from + i}", kind, "Film ${from + i}", poster = "https://img.example/p/${from + i}")) }
    }
}
