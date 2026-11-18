package app.fireplayer.vod

/*
 * The contract between Films & Shows' data (where titles come from, what's in My List) and its pages (Home, Movies,
 * TV Shows). Decisions behind it: design-brief.md §6. Titles come from Cinemeta (rows, details) and TMDB (Top 10);
 * playback sources are a later step and are not part of this file.
 *
 * Threading: every callback here arrives on the main thread. Work happens on background threads owned by the
 * repository; nothing here blocks.
 */

/** Film or series. */
enum class Kind { MOVIE, SERIES }

/** A film or series as FirePlayer shows it. One per IMDb id ("tt…"), whichever row it appears in. */
data class Title(
    val id: String,
    val kind: Kind,
    val name: String,
    /** "2024", or "2012–2014" for a series. */
    val year: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    /** IMDb rating as Cinemeta gives it, e.g. "7.5". */
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    /** "2h 6m" or "23 min", as given. */
    val runtime: String? = null,
    val tmdbId: Int? = null,
)

/** Which episode a card is about (Continue Watching, Next Episodes). */
data class EpisodeRef(val season: Int, val episode: Int, val name: String? = null)

/** One card in a row: the title plus what that row adds to it. */
data class Card(
    val title: Title,
    /** 0..1 for Continue Watching; null elsewhere. */
    val progress: Float? = null,
    /** The line under the name on wide cards, e.g. "12m left" or "S2 E5 · 12m left". */
    val caption: String? = null,
    /** 1..10 in Top 10 rows. */
    val rank: Int? = null,
    val episode: EpisodeRef? = null,
    /** A badge such as "NEW" (Next Episodes). */
    val badge: String? = null,
)

enum class Page { HOME, MOVIES, SHOWS }

/** How a row draws its cards. Sizes are in design-brief.md §6.3. */
enum class RowStyle {
    /** 3:4 posters, 6.5 across. */
    POSTER,
    /** 16:9 cards with name, caption and progress inside (Continue Watching), 4.5 across. */
    WIDE,
    /** Posters with Glass Home's big rank number beside each (Top 10). */
    RANKED,
    /** A row of genre chips; OK opens that genre's "See all". */
    GENRES,
}

/** Where a row's cards come from. */
sealed class Source {
    /** A Cinemeta catalog: "top" (Popular), "year" (New in a year; [genre] holds the year), "imdbRating" (Featured). */
    data class Catalog(val kind: Kind, val catalog: String, val genre: String? = null) : Source()

    /** TMDB's weekly trending list, first 10, mapped to IMDb ids. Needs the user's TMDB key; empty without it. */
    data class Trending(val kind: Kind) : Source()

    /** FirePlayer's Continue Watching: films, series, or both ([kind] null). Empty until playback records some. */
    data class ContinueWatching(val kind: Kind?) : Source()

    /** FirePlayer's My List (a saved-title list, independent of Real-Debrid). */
    data object MyList : Source()

    /** The next unwatched episode of series the user is following. Empty until playback records some. */
    data class NextEpisodes(val kind: Kind = Kind.SERIES) : Source()

    /** Cinemeta's genre names for [kind] (chips). */
    data class Genres(val kind: Kind) : Source()
}

/**
 * One row on a page.
 * [key] is stable (settings, focus memory); [continueRow] marks the row whose focused title the header shows and
 * whose cards go straight to playback.
 */
data class RowSpec(
    val key: String,
    val label: String,
    val style: RowStyle,
    val source: Source,
    val continueRow: Boolean = false,
)

/** Stops a load: its callback won't be called after this. */
fun interface Cancel {
    fun cancel()
}

/** What the page shows while idle in its header: rotated through, about every 8 s. */
data class HeaderFeed(val titles: List<Title>)

interface VodRepository {
    /** The rows of [page] in order (only the ones switched on). Rows whose source turns out empty are hidden by the page. */
    fun rows(page: Page): List<RowSpec>

    /**
     * Cards for [row], up to [limit] starting at [skip]. [onCards] is called first with whatever is cached (fresh =
     * false, possibly empty) and then once more with fresh data (fresh = true) if the cache was old or missing. On a
     * network failure with nothing cached it is called once with an empty list and fresh = true.
     */
    fun load(row: RowSpec, skip: Int = 0, limit: Int = 15, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel

    /** Genre names for a GENRES row. */
    fun genres(kind: Kind, onGenres: (List<String>) -> Unit): Cancel

    /** The header's idle rotation for [page]: the page's Top 10 (Home: films and series mixed), or Cinemeta Popular's first 10 without a TMDB key. */
    fun header(page: Page, onFeed: (HeaderFeed) -> Unit): Cancel

    /** Whether a TMDB key is set (Top 10, age ratings, cast photos, More Like This). */
    fun hasTmdbKey(): Boolean

    // ---- The user's library (stored on this TV only; no syncing) ----

    fun inMyList(id: String): Boolean
    fun setInMyList(title: Title, inList: Boolean)
    fun isWatched(id: String): Boolean
    fun setWatched(title: Title, watched: Boolean)

    /** Takes a title out of Continue Watching (its resume point stays). */
    fun removeFromContinueWatching(id: String)

    /** Called on the main thread whenever My List, watched marks or Continue Watching change, so rows can refresh. */
    fun addLibraryListener(listener: () -> Unit)
    fun removeLibraryListener(listener: () -> Unit)
}
