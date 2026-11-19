package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Position
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.StartAt
import java.util.Locale

/**
 * The words and small decisions of playing a film or an episode from the add-ons (design-brief.md §6.4, 6.5): what the
 * waiting strip and the Play pill say while the add-ons answer, the title page's source line, the Sources button's
 * count, a source's two lines in the Sources panel, where playback starts and which episode is next. Pure, so the
 * pages and the player only show what these say (unit-tested).
 *
 * Never a link here: only what the add-ons wrote about a file ([PlaybackSource.facts], [app.fireplayer.vod.Offer.text]).
 */
object PlayRules {

    /** The title page's look-up: 0.5 s with a resume point, or opened from Next Episodes or a Continue card's menu. */
    fun pageLookup(hasResumePoint: Boolean, soon: Boolean): LookupWhy =
        if (hasResumePoint || soon) LookupWhy.PAGE_SOON else LookupWhy.PAGE

    /** Where playback starts: a resumable [position] (FirePlayer 1.0's rule), else the beginning; never a question. */
    fun startAt(position: Position?, fromBeginning: Boolean): StartAt =
        if (!fromBeginning && position != null && position.resumable) StartAt.At(position.positionMs) else StartAt.Beginning

    /** A film's start from its Continue Watching record. */
    fun startAt(last: LastPlayed?): StartAt = if (last != null && last.resumable) StartAt.At(last.position.positionMs) else StartAt.Beginning

    // ---- Waiting for the add-ons (stage 1) -------------------------------------------------------------------------

    /** One add-on's part of the strip: "Torrentio ✓ 33", "MediaFusion still looking 9 s of 25"; null when not set. */
    fun stateText(addon: Addon, state: AddonState, now: Long): String? = when (state) {
        AddonState.NotSet -> null
        AddonState.NotAsked -> "${addon.label} still looking"
        is AddonState.Asking -> {
            val gone = ((now - state.since).coerceAtLeast(0) / 1000)
            val limit = ((state.deadline - state.since).coerceAtLeast(0) + 999) / 1000
            "${addon.label} still looking $gone s of $limit"
        }
        is AddonState.Answered -> "${addon.label} ✓ ${state.count}"
        is AddonState.Failed -> "${addon.label} didn't answer"
        is AddonState.Busy -> "${addon.label} busy"
    }

    /**
     * The strip under the header while Play waits: each add-on's state, then what the keys do ("OK: play best so far"
     * only once there is one).
     */
    fun strip(list: SourceList, now: Long): String {
        val parts = Addon.entries.mapNotNull { a -> list.states[a]?.let { stateText(a, it, now) } }.toMutableList()
        if (list.best != null) parts += "OK: play best so far"
        parts += "Back: cancel"
        return parts.joinToString(" · ")
    }

    /** The Play pill while waiting: "Waiting for MediaFusion · 9 s", "Waiting for the add-ons · 4 s". */
    fun waitingLabel(list: SourceList, now: Long, pressedAt: Long): String {
        val still = list.stillLooking
        val who = when (still.size) {
            0 -> "sources"
            1 -> still[0].label
            else -> "the add-ons"
        }
        val secs = ((now - pressedAt).coerceAtLeast(0) / 1000)
        return "Waiting for $who · $secs s"
    }

    /** The Play pill's thin bar while waiting: how far toward the last add-on's answer limit, 0..1. */
    fun waitFraction(list: SourceList, now: Long): Float {
        val asking = list.states.values.filterIsInstance<AddonState.Asking>()
        if (asking.isEmpty()) return 1f
        val since = asking.minOf { it.since }
        val deadline = asking.maxOf { it.deadline }
        if (deadline <= since) return 1f
        return ((now - since).toFloat() / (deadline - since)).coerceIn(0f, 1f)
    }

    /**
     * Whether the wait is over though the list isn't [SourceList.complete]: every add-on still asking is [GRACE_MS] past
     * its answer limit. The engine marks those as failed itself; this keeps a stuck answer from holding Play forever.
     */
    fun waitedOut(list: SourceList, now: Long): Boolean {
        if (list.complete) return true
        return list.states.values.all { s ->
            s.final || (s is AddonState.Asking && now > s.deadline + GRACE_MS)
        }
    }

    /** What OK plays after the wait: the best automatic pick, or (none ready) any source, which the player asks about. */
    fun playable(list: SourceList): Boolean = list.best != null || list.sources.isNotEmpty()

    // ---- The title page's source line and Sources button -----------------------------------------------------------

    /**
     * The source line once an add-on has answered: "Best for this TV · 4K · DV · WEB-DL · 29.3 GB · from Torrentio"
     * ("Best so far" while the other still looks; "Last used" when that release is offered again). "No sources found"
     * when every add-on answered with none; null before any answer (the line stays empty).
     */
    fun sourceLine(list: SourceList?): String? {
        if (list == null) return null
        val answered = list.states.values.any { it is AddonState.Answered }
        if (!answered) {
            if (list.complete && list.states.values.all { it == AddonState.NotSet }) return NO_ADDONS_LINE
            if (list.complete) return "The add-ons didn't answer · Sources to try again"
            return null
        }
        val pick = list.lastUsed ?: list.best
        if (pick == null) {
            return if (list.sources.isEmpty()) {
                if (list.complete) "No sources found" else null
            } else "None ready on Real-Debrid · ${list.sources.size} to fetch in Sources"
        }
        val lead = when {
            list.lastUsed != null -> "Last used"
            list.complete -> "Best for this TV"
            else -> "Best so far"
        }
        return listOf(lead, badges(pick), "from ${addonsOf(pick)}").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The Sources button: "Sources", then "Sources · 40" once an add-on has answered. */
    fun sourcesLabel(list: SourceList?): String {
        val answered = list?.states?.values?.any { it is AddonState.Answered } == true
        return if (answered) "Sources · ${list!!.sources.size}" else "Sources"
    }

    // ---- A source, as the Sources panel and the player show it ------------------------------------------------------

    /** "4K", "1080p", "720p"…; null when the add-ons didn't say. */
    fun resolution(height: Int?): String? = when {
        height == null -> null
        height >= 2000 -> "4K"
        else -> "${height}p"
    }

    /** Line 1: resolution, HDR kinds, origin, size and average rate, pack ("4K · DV · HDR10+ · WEB-DL · 29.3 GB · ≈25 Mb/s"). */
    fun badges(s: PlaybackSource): String {
        val f = s.facts
        val hdr = Hdr.entries.filter { it in f.hdr }.map { h ->
            if (h == Hdr.DOLBY_VISION && f.dvProfile != null) "DV${f.dvProfile}" else h.label
        }
        return listOfNotNull(
            resolution(f.height),
            *hdr.toTypedArray(),
            f.origin,
            f.sizeBytes?.let { size(it) },
            f.avgMbps?.let { "≈${it.toInt()} Mb/s" },
            if (f.pack) "Pack" else null,
            if (s.copies > 1) "×${s.copies}" else null,
        ).joinToString(" · ")
    }

    /** Line 2: the one warning first, then codec, sound, group, languages, seeders for files not on Real-Debrid. */
    fun facts(s: PlaybackSource): String {
        val f = s.facts
        val codec = listOfNotNull(f.codec, f.bitDepth?.takeIf { it > 8 }?.let { "$it-bit" }).joinToString(" ").ifEmpty { null }
        val sound = listOfNotNull(f.audio, f.channels).joinToString(" ").ifEmpty { null }
        val langs = f.languages.takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.uppercase(Locale.ROOT) }
        val seeds = if (s.readiness == Readiness.NOT_READY) f.seeders?.let { "$it seeders" } else null
        return listOfNotNull(s.warning?.let { "⚠ $it" }, codec, sound, f.group, langs, seeds).joinToString(" · ")
    }

    /** "Torrentio", "MediaFusion", or "Both add-ons". */
    fun addonsOf(s: PlaybackSource): String = if (s.bothAddons) "Both add-ons" else s.addons.firstOrNull()?.label.orEmpty()

    /** Where a source's facts came from: "from Torrentio" until FirePlayer measured it ("✓ played Nov 12"). */
    fun factsFrom(s: PlaybackSource, day: (Long) -> String): String {
        val at = s.measured?.at
        return if (at != null) "✓ played ${day(at)}" else "from ${addonsOf(s)}"
    }

    /** "29.3 GB", "870 MB". */
    fun size(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1.0) String.format(Locale.ROOT, "%.1f GB", gb) else "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"
    }

    /** "1:02:03" or "42:10", for "Resumed near 1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    // ---- After a switch, and the next episode --------------------------------------------------------------------

    /** A switch starts this much before where the old source was (§6.5, "5 s early"). */
    const val SWITCH_BACK_MS = 5_000L

    /** Where a switched-to source starts: 5 s before [positionMs], never below 0. */
    fun switchStart(positionMs: Long): Long = (positionMs - SWITCH_BACK_MS).coerceAtLeast(0L)

    /** The next episode's sources are looked up in this last part of an episode (§6.5, "the last ~4 minutes"). */
    const val NEXT_LOOKUP_MS = 4 * 60_000L

    /**
     * The aired regular episode after [current] in [episodes] (any order), across seasons; specials never. Null at the
     * end, or when the next hasn't aired by [now].
     */
    fun nextEpisode(episodes: List<Episode>, current: EpisodeRef, now: Long): Episode? {
        if (current.season <= 0) return null
        val regular = episodes.filter { it.season > 0 }.sortedWith(compareBy<Episode>({ it.season }, { it.episode }))
        val after = regular.firstOrNull { it.season > current.season || (it.season == current.season && it.episode > current.episode) }
        return after?.takeIf { it.aired(now) }
    }

    /** Seconds past an answer limit before the title page stops waiting on its own ([waitedOut]). */
    const val GRACE_MS = 2_000L

    const val NO_ADDONS_LINE = "No add-ons yet · add Torrentio or MediaFusion in Settings › Add-ons"
}
