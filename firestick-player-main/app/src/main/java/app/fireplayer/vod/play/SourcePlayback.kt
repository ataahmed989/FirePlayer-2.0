package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Offer
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import java.util.concurrent.ConcurrentHashMap

/**
 * The player's side of a play from the add-ons (design-brief.md §6.5, stages 2-4), so FirePlayer's player keeps its own
 * design and only calls in here: the loading screen's words while the link is checked, the first-load limit with Keep
 * waiting / Try next source, the stall watch and automatic switches, Next source and the Sources panel's picks, the
 * failure table's steps, the next episode, and what the library records. The [SourceSession] decides which source;
 * this says what the player does about it ([Host]).
 *
 * Links stay in [Opened.Ready.link] (memory only): handed to [Host.startSource], never kept, logged or shown here.
 * Main thread.
 */
class SourcePlayback(
    val request: PlayRequest,
    val session: SourceSession,
    private val library: PlaybackLibrary,
    private val ticker: Ticker,
    private val host: Host,
) {

    interface Host {
        /** The loading screen's line (stages 1 and 2): "Checking the link · 4K · DV · from Torrentio". */
        fun showProgress(text: String)

        /**
         * Play [ready]'s link from [startMs]: the first source ([switching] false: the player is built for it), or
         * another in place of the one playing ([switching] true).
         */
        fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean)

        /** A question with its answers ("Keep waiting" / "Try next source"). */
        fun ask(question: String, choices: List<Pair<String, () -> Unit>>)

        /** Nothing more to try: say [message], and Back returns to the page (on its Sources button). */
        fun stopWith(message: String)

        /** A short note over the film ("Resumed near 1:02:03"). */
        fun notice(text: String)

        /** Where the film is now, ms (0 before it plays). */
        fun positionMs(): Long

        /** The same link again after [ms] (the failure table's retry). */
        fun retryAfter(ms: Long)

        /** The receiver refused the sound: decode it on the Stick for this play, same source. */
        fun decodeOnDevice()

        /** The next episode, from its own sources: a new play in place of this one. */
        fun playNext(next: PlayRequest)
    }

    /** The source playing (or being opened), and the add-on offer it came from. */
    var current: PlaybackSource? = null
        private set
    private var offer: Offer? = null
    /** The current source has shown its first frame (stage 4 from here; before it, stage 3). */
    var playing = false
        private set
    private var everPlayed = false
    private var opening: Cancel? = null
    private var loadTimer: Cancel? = null
    private var nextLookup: Cancel? = null
    private var nextList: SourceList? = null
    private var nextLooked = false
    /** The switch limit's note was shown once. */
    private var limitNoted = false
    /** The next open plays from the beginning (a Sources row held: Play from beginning). */
    private var fromBeginning = false
    private var closed = false
    /** The play stopped with a message (nothing more is tried until a new source is ready). */
    private var stopped = false

    /** The episode after this one (the page's, or found by the player), for the next episode's play; null for a film. */
    var next: EpisodeRef? = request.next

    /** Whether [request] is for an episode. */
    val isEpisode: Boolean get() = request.target.episode != null

    /** The first source: the row chosen in Sources (asked about already when not on Real-Debrid), else the session's pick. */
    fun start() {
        val chosen = request.chosen
        val agreed = chosen != null && chosen.readiness == Readiness.NOT_READY && request.origin == PlayOrigin.SOURCES_PANEL
        if (agreed && chosen != null) {
            opening = session.fetch(chosen) { onOpened(it, OpenWhy.USER) }
        } else {
            open(chosen, OpenWhy.START)
        }
    }

    /** Next source (Picture & sound): another file, at the current position. Always allowed. */
    fun nextSource() = open(null, OpenWhy.USER)

    /** A row picked in the player's Sources panel; [beginning] from its start (held OK › Play from beginning). */
    fun pick(source: PlaybackSource, beginning: Boolean) {
        if (source.readiness == Readiness.NOT_READY) {
            fromBeginning = beginning
            opening?.cancel()
            opening = session.fetch(source) { onOpened(it, OpenWhy.USER) }
            return
        }
        fromBeginning = beginning
        open(source, OpenWhy.USER)
    }

    private fun open(source: PlaybackSource?, why: OpenWhy) {
        if (closed) return
        opening?.cancel()
        opening = session.open(source, why) { onOpened(it, why) }
    }

    private fun onOpened(o: Opened, why: OpenWhy) {
        if (closed) return
        when (o) {
            is Opened.Looking -> if (!playing) host.showProgress(lookingText(o.list))
            is Opened.Checking -> if (!playing) host.showProgress(checkingText(o.source))
            is Opened.Ready -> ready(o)
            is Opened.AskFetch -> askFetch(o.source)
            is Opened.Stopped -> end(o.message)
            is Opened.NoneLeft -> end(o.message)
        }
    }

    private fun ready(o: Opened.Ready) {
        // A source was handed to the player before (it's built): this one takes its place.
        val switching = current != null
        val start = when {
            fromBeginning -> 0L
            o.startMs != null -> o.startMs
            // Once a source played, a new one starts a little before where it was (§6.5).
            everPlayed -> PlayRules.switchStart(host.positionMs())
            else -> when (val s = request.start) {
                StartAt.Beginning -> 0L
                is StartAt.At -> s.positionMs
            }
        }
        fromBeginning = false
        stopped = false
        current = o.source
        offer = o.offer
        playing = false
        loadTimer?.cancel()
        host.startSource(o, start, switching)
        if ((o.resumedNear || everPlayed) && start > 0) host.notice("Resumed near ${PlayRules.clock(start)}")
        armLoad(o.source)
    }

    private fun askFetch(s: PlaybackSource) {
        host.ask(
            "None of the ready sources played. Ask Real-Debrid to fetch ${PlayRules.badges(s).ifEmpty { "this source" }}? It can take a while and uses a download slot.",
            listOf(
                "Ask Real-Debrid to fetch it" to {
                    opening?.cancel()
                    opening = session.fetch(s) { onOpened(it, OpenWhy.USER) }
                },
                "Not now" to { end(NOT_FETCHED) },
            ),
        )
    }

    /** Over: while a source plays, a note (it plays on); before any did, the play stops with [message]. */
    private fun end(message: String) {
        if (playing) {
            host.notice(message)
            return
        }
        loadTimer?.cancel()
        stopped = true
        PlayOutcomes.put(request.target, message)
        host.stopWith(message)
    }

    // ---- Stage 3: the first load ------------------------------------------------------------------------------------

    /** The player left the screen: stage 3's limit waits (nothing is asked over a screen that isn't there). */
    fun pause() {
        loadTimer?.cancel()
        loadTimer = null
    }

    /** Back on screen: a source still loading gets its limit afresh (the player loads it again from here). */
    fun resume() {
        val s = current ?: return
        if (!playing && !closed && !stopped && loadTimer == null) armLoad(s)
    }

    private fun armLoad(s: PlaybackSource) {
        loadTimer?.cancel()
        loadTimer = ticker.after(session.firstLoadLimitMs(s)) {
            if (closed || playing || current?.key != s.key) return@after
            host.ask(
                "This source is slow to start.",
                listOf(
                    "Keep waiting" to { if (!playing && current?.key == s.key) armLoad(s) },
                    "Try next source" to { if (!playing && current?.key == s.key) act(session.failed(s, Failure(FailureKind.SLOW, offer?.addon))) },
                ),
            )
        }
    }

    /** The current source's first frame: recorded as the last release used; the stall watch starts. */
    fun firstFrame() {
        val s = current ?: return
        if (playing) return
        playing = true
        everPlayed = true
        loadTimer?.cancel()
        loadTimer = null
        PlayOutcomes.clear(request.target)
        offer?.let { session.started(s, it) }
        val now = ticker.now()
        session.stalls.jumped(now)
        session.stalls.playing(now)
    }

    // ---- Stage 4: while playing -------------------------------------------------------------------------------------

    /** The player started or stopped loading while playing. */
    fun buffering(on: Boolean) {
        if (!playing) return
        val now = ticker.now()
        session.stalls.buffering(now, on)
        if (!on) session.stalls.playing(now)
        checkStalls(now)
    }

    /** A seek or a resume: stalls right after it don't count. */
    fun jumped() {
        session.stalls.jumped(ticker.now())
    }

    private fun checkStalls(now: Long) {
        if (!session.stalls.shouldSwitch(now)) return
        if (session.maySwitch(now)) {
            open(null, OpenWhy.STALLS)
        } else if (!limitNoted) {
            limitNoted = true
            host.notice(LIMIT_NOTE)
        }
    }

    // ---- Failures ---------------------------------------------------------------------------------------------------

    /** A player error the player's own quiet retries didn't get past (PlaybackException code, HTTP status). */
    fun failed(errorCode: Int, httpStatus: Int?) {
        val s = current ?: return end(GENERIC_STOP)
        val failure = session.failureOf(errorCode, httpStatus)
        playing = false
        act(session.failed(s, failure))
    }

    private fun act(step: Step) {
        when (step) {
            Step.TryNext -> open(null, OpenWhy.FAILED)
            is Step.Retry -> host.retryAfter(step.afterMs)
            Step.Refresh -> open(current, OpenWhy.FAILED)
            Step.DecodeOnDevice -> host.decodeOnDevice()
            is Step.Stop -> end(step.message)
        }
    }

    // ---- The library and the next episode --------------------------------------------------------------------------

    /** Where playback is (on leaving, and now and then): the resume point, Continue Watching and the watched mark. */
    fun record(positionMs: Long, durationMs: Long, creditsAtMs: Long?) {
        if (!everPlayed || durationMs <= 0) return
        library.recordPlayback(request.target.title, request.target.episode, positionMs, durationMs, creditsAtMs)
    }

    /** Called as it plays: in the last ~4 minutes of an episode, the next one's sources are looked up once. */
    fun nearEnd(positionMs: Long, durationMs: Long) {
        val n = next ?: return
        if (nextLooked || durationMs <= 0 || durationMs - positionMs > PlayRules.NEXT_LOOKUP_MS) return
        nextLooked = true
        nextLookup = session.lookUpNext(n) { nextList = it }
    }

    /** The episode played to its end: the next one starts from its own sources (true), or there's none (false). */
    fun ended(playNext: Boolean): Boolean {
        val n = next ?: return false
        if (!playNext) return false
        host.playNext(nextRequest(n))
        return true
    }

    /** The next episode's play: from the start, its list when already complete, the same kind of source preferred. */
    fun nextRequest(n: EpisodeRef): PlayRequest = PlayRequest(
        target = SourceTarget(request.target.title, n),
        start = StartAt.Beginning,
        origin = PlayOrigin.NEXT_EPISODE,
        list = nextList?.takeIf { it.complete && it.target.episode == n },
        like = current,
        filmMode = request.filmMode,
    )

    /** The add-on the playing source came from, for the player's details ("from Torrentio"). */
    val addon: Addon? get() = offer?.addon

    fun close() {
        if (closed) return
        closed = true
        opening?.cancel()
        loadTimer?.cancel()
        nextLookup?.cancel()
        session.close()
    }

    private fun lookingText(l: SourceList): String {
        val now = ticker.now()
        val states = Addon.entries.mapNotNull { a -> l.states[a]?.let { PlayRules.stateText(a, it, now) } }
        return (listOf("Finding sources") + states).joinToString(" · ")
    }

    private fun checkingText(s: PlaybackSource): String =
        listOf("Checking the link", PlayRules.badges(s), "from ${PlayRules.addonsOf(s)}").filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        const val LIMIT_NOTE = "Playback keeps pausing · Next source in Picture & sound tries another"
        const val NOT_FETCHED = "Nothing played · Sources lists every file"
        const val GENERIC_STOP = "This title couldn't play · Sources lists every file"
    }
}

/**
 * Why the last play of a film or episode stopped, kept in memory for its title page: Back from a play that gave up lands
 * on the page with the reason in the source line and focus on Sources (r3_title-page §3.6). Only plain words, never links.
 */
object PlayOutcomes {
    private val stopped = ConcurrentHashMap<String, String>()

    fun put(target: SourceTarget, message: String) {
        stopped[target.key] = message
    }

    fun clear(target: SourceTarget) {
        stopped.remove(target.key)
    }

    /** The reason [target]'s last play stopped, once (it's gone after this). */
    fun take(target: SourceTarget): String? = stopped.remove(target.key)
}
