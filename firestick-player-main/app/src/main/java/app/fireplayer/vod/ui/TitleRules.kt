package app.fireplayer.vod.ui

import app.fireplayer.vod.Callout
import app.fireplayer.vod.Card
import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.PlayAction
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.Position
import app.fireplayer.vod.Season
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.Captions
import kotlin.math.roundToInt

/**
 * What the title page says (pure, unit-tested; design-brief.md §6.4, layout A): its facts line and the one callout
 * beside it, the cast line, the Play button's words for a film or a show, the season pills, an episode card's two lines
 * and the facts line while an episode card is chosen, Details' facts, and where the D-pad goes between its parts.
 * Dates are the [Day]s the data gives (an episode's US air date); [thisYear] decides whether a date says its year.
 */
internal object TitleRules {

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    // ---- Words for numbers and dates ----

    /** "2h 47m", "1h", "47m"; null for none. */
    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** "Oct 5, 2013". */
    fun dayText(d: Day): String = "${MONTHS[(d.month - 1).coerceIn(0, 11)]} ${d.day}, ${d.year}"

    /** "Dec 3" this year, "Jan 7, 2027" another. */
    fun shortDay(d: Day, thisYear: Int): String =
        if (d.year == thisYear) "${MONTHS[(d.month - 1).coerceIn(0, 11)]} ${d.day}" else dayText(d)

    // ---- The header ----

    /**
     * The facts line, the parts that exist. A film: "2024 · 2h 47m · ★ 8.4 · PG-13 · Action, Adventure, Drama"; a show:
     * "2012–2014 · 4 seasons · Ended · ★ 8.3 · TV-Y7 · Animation, Action, Adventure". The age rating is TMDB's only.
     */
    fun facts(d: TitleDetails): String {
        val t = d.title
        val year = t.year?.takeIf { it.isNotBlank() }
        val rating = t.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" }
        val age = d.ageRating?.takeIf { it.isNotBlank() }
        val genres = t.genres.filter { it.isNotBlank() }.take(MAX_GENRES).joinToString(", ").ifEmpty { null }
        val parts = if (t.kind == Kind.MOVIE) {
            listOf(year, runtime(d.runtimeMin), rating, age, genres)
        } else {
            val n = d.seasonCount
            val seasons = if (n <= 0) null else if (n == 1) "1 season" else "$n seasons"
            val ended = d.status?.takeIf { it.equals("Ended", ignoreCase = true) }?.let { "Ended" }
            listOf(year, seasons, ended, rating, age, genres)
        }
        return parts.filterNotNull().joinToString(" · ")
    }

    /** "Starring A, B, C · Director D" (a film) or "· Created by …" (a show); the parts that exist. */
    fun castLine(d: TitleDetails): String {
        val cast = d.cast?.value.orEmpty().filter { it.isNotBlank() }.take(CAST_SHOWN)
        val starring = cast.takeIf { it.isNotEmpty() }?.let { "Starring ${it.joinToString(", ")}" }
        val crew = if (d.title.kind == Kind.MOVIE) {
            val dirs = d.directors?.value.orEmpty().filter { it.isNotBlank() }.take(CREW_SHOWN)
            when (dirs.size) {
                0 -> null
                1 -> "Director ${dirs[0]}"
                else -> "Directors ${dirs.joinToString(", ")}"
            }
        } else {
            d.creators?.value.orEmpty().filter { it.isNotBlank() }.take(CREW_SHOWN).takeIf { it.isNotEmpty() }
                ?.let { "Created by ${it.joinToString(", ")}" }
        }
        return listOfNotNull(starring, crew).joinToString(" · ")
    }

    /** The whole story (the header shows 3 lines of it). */
    fun story(d: TitleDetails): String = d.story?.value?.trim().orEmpty()

    /**
     * The one callout, in the brief's order: this week's Top 10; then a film watched ("✓ Watched") or a show's
     * episodes (all watched, new ones, or caught up); then an Oscar line.
     */
    fun callout(d: TitleDetails, filmWatched: Boolean, allWatched: Boolean, target: PlayTarget?): Callout? {
        d.top10Rank?.takeIf { it in 1..10 }?.let { return Callout.Top10(it) }
        if (d.title.kind == Kind.MOVIE) {
            if (filmWatched) return Callout.Watched
        } else {
            if (allWatched) return Callout.WatchedAll
            if (target?.newEpisodes == true) return Callout.NewEpisodes
            target?.upcoming?.let { return Callout.UpToDate(it) }
        }
        d.oscars?.takeIf { it.isNotBlank() }?.let { return Callout.Oscars(it) }
        return null
    }

    fun calloutText(c: Callout, thisYear: Int): String = when (c) {
        is Callout.Top10 -> "#${c.rank} in Top 10 this week"
        Callout.Watched -> "✓ Watched"
        Callout.WatchedAll -> "✓ Watched all"
        Callout.NewEpisodes -> "New episodes"
        is Callout.UpToDate -> {
            val day = c.next.day?.let { " airs ${shortDay(it, thisYear)}" } ?: " coming"
            "Up to date · ${episodeCode(c.next.ref)}$day"
        }
        is Callout.Oscars -> c.text
    }

    /** Whether every aired regular episode is watched (and at least one has aired): "✓ Watched all". */
    fun allWatched(episodes: List<Episode>, now: Long, watched: (Episode) -> Boolean): Boolean {
        val aired = episodes.filter { it.season > 0 && it.aired(now) }
        return aired.isNotEmpty() && aired.all(watched)
    }

    // ---- The Play button ----

    /** Play's words and state: dimmed and passed by when it can't play yet; [restart] offers Play from beginning. */
    data class Play(val label: String, val progress: Float? = null, val enabled: Boolean = true, val restart: Boolean = false)

    /**
     * A film's Play: "Play", "Resume · 42m left" (FirePlayer 1.0: a position past 30 s and not in the last 3 minutes,
     * [LastPlayed.resumable]) with its progress line, or "Coming Mar 6" when it isn't out ([TitleDetails.isOut]).
     */
    fun filmPlay(d: TitleDetails, last: LastPlayed?, now: Long, thisYear: Int): Play {
        if (!d.isOut(now, thisYear)) return Play(coming(d, thisYear), enabled = false)
        if (last != null && last.resumable) {
            val left = last.position.leftMs?.let { Captions.left(it) }
            return Play(listOfNotNull("Resume", left).joinToString(" · "), last.position.fraction, restart = true)
        }
        return Play("Play")
    }

    /** "Coming Mar 6", "Coming Mar 6, 2027", "Coming 2027", or "Coming soon". */
    fun coming(d: TitleDetails, thisYear: Int): String {
        d.released?.let { return "Coming ${shortDay(it, thisYear)}" }
        d.title.year?.take(4)?.toIntOrNull()?.let { return "Coming $it" }
        return "Coming soon"
    }

    /**
     * A show's Play: "Play S1 E1", "Resume S2 E5 · 12m left" (with Play from beginning: this episode again), "Play S2
     * E6", "Watch again · S1 E1"; "Coming Nov 20" when no episode has aired; "Play" while the episodes aren't known.
     */
    fun showPlay(d: TitleDetails, target: PlayTarget?, thisYear: Int): Play {
        if (target == null) {
            val first = d.episodes.filter { it.season > 0 }.minWithOrNull(compareBy({ it.season }, { it.episode }))
                ?: return Play("Play")
            val day = first.day?.let { "Coming ${shortDay(it, thisYear)}" } ?: "Coming soon"
            return Play(day, enabled = false)
        }
        val code = episodeCode(target.episode.ref)
        return when (target.action) {
            PlayAction.PLAY -> Play("Play $code")
            PlayAction.WATCH_AGAIN -> Play("Watch again · $code")
            PlayAction.RESUME -> {
                val left = target.position?.leftMs?.let { Captions.left(it) }
                Play(listOfNotNull("Resume $code", left).joinToString(" · "), target.position?.fraction, restart = true)
            }
        }
    }

    // ---- Seasons and episodes ----

    /** "Season 2", "Specials". */
    fun seasonLabel(number: Int): String = if (number == 0) "Specials" else "Season $number"

    /** The pills in order: the regular seasons, then Specials last (as the data gives them, sorted again to be sure). */
    fun seasonOrder(seasons: List<Season>): List<Season> =
        seasons.sortedWith(compareBy({ it.number == 0 }, { it.number }))

    /** Pills show only when there's more than one to choose (a season plus Specials is two). */
    fun showsPills(seasons: List<Season>): Boolean = seasons.size > 1

    /** "S2 E5"; a special "Special 3". */
    fun episodeCode(ref: EpisodeRef): String = if (ref.season == 0) "Special ${ref.episode}" else "S${ref.season} E${ref.episode}"

    /** The episode card's first line: "E5" (blue) then " · Peacekeepers". */
    fun episodeNumber(ref: EpisodeRef): String = "E${ref.episode}"

    fun episodeNameAfterNumber(ref: EpisodeRef): String = ref.name?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    fun episodeLine1(ref: EpisodeRef): String = episodeNumber(ref) + episodeNameAfterNumber(ref)

    /** Minutes for "23m": the length playback found (when it has one), else the show's usual episode length. */
    private fun minutesOf(ep: Episode, position: Position?): Int? =
        position?.durationMs?.takeIf { it > 0 }?.let { (it / 60_000.0).roundToInt() } ?: ep.runtimeMin

    /** The card's second line: "23m · Oct 5, 2013"; not aired: "Airs Dec 3". */
    fun episodeMeta(ep: Episode, position: Position?, now: Long, thisYear: Int): String {
        if (!ep.aired(now)) return airs(ep, thisYear)
        return listOfNotNull(runtime(minutesOf(ep, position)), ep.day?.let { dayText(it) }).joinToString(" · ")
    }

    /** "Airs Dec 3", "Airs Jan 7, 2027", or "Not aired yet" with no date. */
    fun airs(ep: Episode, thisYear: Int): String = ep.day?.let { "Airs ${shortDay(it, thisYear)}" } ?: "Not aired yet"

    /** The facts line while an episode card is chosen: "S2 E5 · Peacekeepers · 23m · Oct 5, 2013 · 12m left". */
    fun episodeFacts(ep: Episode, position: Position?, now: Long, thisYear: Int): String {
        val aired = ep.aired(now)
        val left = position?.takeIf { aired && it.resumable }?.leftMs?.let { Captions.left(it) }
        return listOfNotNull(
            episodeCode(ep.ref),
            ep.name.takeIf { it.isNotBlank() },
            if (aired) runtime(minutesOf(ep, position)) else null,
            if (aired) ep.day?.let { dayText(it) } else airs(ep, thisYear),
            left,
        ).joinToString(" · ")
    }

    /**
     * An episode's card ([app.fireplayer.vod.RowStyle.EPISODE]): its id as the card's title id (focus is kept by it),
     * its still, how far it was watched (a bar while it can be resumed), ✓ when watched, NEW when it aired in the last
     * 7 days, dimmed when it hasn't aired.
     */
    fun episodeCard(show: Title, ep: Episode, watched: Boolean, position: Position?, now: Long, thisYear: Int): Card {
        val aired = ep.aired(now)
        val progress = position?.takeIf { aired && !watched && it.resumable }?.fraction
        return Card(
            title = Title(ep.id, Kind.SERIES, ep.name, background = show.background),
            progress = progress,
            caption = episodeMeta(ep, position, now, thisYear),
            episode = ep.ref,
            badge = if (ep.isNew(now)) VodSizes.NEW_BADGE else null,
            picture = ep.still,
            watched = watched,
            playable = aired,
        )
    }

    /** The card focus goes to in a season's [episodes]: the Play target when it's there, else the first. */
    fun focusIndex(episodes: List<Episode>, target: Episode?): Int =
        episodes.indexOfFirst { target != null && it.id == target.id }.coerceAtLeast(0)

    /** The season the page opens on: the Play target's, else the first regular one, else the first. */
    fun openingSeason(seasons: List<Season>, target: Episode?): Int? {
        if (target != null && seasons.any { it.number == target.season }) return target.season
        return seasons.firstOrNull { it.number > 0 }?.number ?: seasons.firstOrNull()?.number
    }

    // ---- Details (a SidePanel window) ----

    /** Details' facts, the ones that exist, as name and value. */
    fun detailsFacts(d: TitleDetails): List<Pair<String, String>> {
        val t = d.title
        val out = ArrayList<Pair<String, String>>()
        t.year?.takeIf { it.isNotBlank() }?.let { out += "Year" to it }
        d.released?.let { out += (if (t.kind == Kind.MOVIE) "Released" else "First aired") to dayText(it) }
        if (t.kind == Kind.SERIES) {
            d.seasonCount.takeIf { it > 0 }?.let { out += "Seasons" to it.toString() }
            d.status?.takeIf { it.isNotBlank() }?.let { out += "Status" to it }
        }
        runtime(d.runtimeMin)?.let { out += (if (t.kind == Kind.MOVIE) "Length" else "Episodes") to it }
        d.ageRating?.takeIf { it.isNotBlank() }?.let { out += "Rated" to it }
        t.genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { out += "Genres" to it.joinToString(", ") }
        d.country?.takeIf { it.isNotBlank() }?.let { out += "Country" to it }
        t.imdbRating?.takeIf { it.isNotBlank() }?.let { out += "IMDb" to "★ $it" }
        d.awards?.takeIf { it.isNotBlank() }?.let { out += "Awards" to it }
        return out
    }

    /** Where Details' facts came from: "Story from TMDB", "Cast from Cinemeta", … (the ones it has). */
    fun sourceNotes(d: TitleDetails): List<String> {
        fun from(f: From) = if (f == From.TMDB) "TMDB" else "Cinemeta"
        val notes = ArrayList<String>()
        d.story?.let { notes += "Story from ${from(it.from)}" }
        d.cast?.let { notes += "Cast from ${from(it.from)}" }
        (d.directors ?: d.creators ?: d.writers)?.let { notes += "Crew from ${from(it.from)}" }
        if (d.ageRating != null) notes += "Age rating from TMDB"
        notes += "Other facts from Cinemeta"
        return notes
    }

    // ---- Where the D-pad goes ----

    /** The page's parts, top to bottom. */
    enum class Part { BUTTONS, PILLS, EPISODES, MORE }

    /** Which parts a page has now. */
    data class Parts(val pills: Boolean, val episodes: Boolean, val more: Boolean)

    /**
     * Down from [from]: the buttons go straight to the episode cards (a show; the pills are reached going Up) or More
     * Like This (a film); pills to cards; cards to More Like This. Null: nowhere (consumed).
     */
    fun down(from: Part, has: Parts): Part? = when (from) {
        Part.BUTTONS -> if (has.episodes) Part.EPISODES else if (has.more) Part.MORE else null
        Part.PILLS -> if (has.episodes) Part.EPISODES else if (has.more) Part.MORE else null
        Part.EPISODES -> if (has.more) Part.MORE else null
        Part.MORE -> null
    }

    /** Up from [from]: More Like This to the cards (or the buttons), cards to the pills (or the buttons), pills to the buttons. */
    fun up(from: Part, has: Parts): Part? = when (from) {
        Part.BUTTONS -> null
        Part.PILLS -> Part.BUTTONS
        Part.EPISODES -> if (has.pills) Part.PILLS else Part.BUTTONS
        Part.MORE -> if (has.episodes) Part.EPISODES else Part.BUTTONS
    }

    /** What Back does with focus in [at]: from a row, back to the buttons; from the buttons, leave the page. */
    fun backLeaves(at: Part): Boolean = at == Part.BUTTONS

    /** Whether a show's page is glided up with focus in [at] (only More Like This needs it). */
    fun glided(at: Part, isShow: Boolean): Boolean = isShow && at == Part.MORE

    private const val MAX_GENRES = 3
    private const val CAST_SHOWN = 3
    private const val CREW_SHOWN = 2
}
