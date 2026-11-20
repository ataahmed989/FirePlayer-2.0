package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList

/**
 * What the Sources panel lists (design-brief.md §6.5, r3_sources-organize §4), pure: at the top the add-ons' states and
 * the totals, then Best for this TV (the best and, when it differs, Last used), then 4K, 1080p, 720p and lower, Other,
 * Cinema recordings and Not on Real-Debrid yet. The first two groups start open with 5 rows and "Show N more"; the last
 * four start closed with one "Show N" row. Every item has a stable [Item.key], so rows keep focus as answers arrive.
 */
object SourcesModel {

    enum class Kind { NOTE, HEADING, SOURCE, SHOW_MORE }

    /**
     * One entry. A [Kind.SOURCE] row has [source], its two lines ([label] and [note]) and its state on the right
     * ([value]: "Best", "Last used", "Playing", "Tried 20:41 · didn't start", then the add-on on a second line).
     * A [Kind.SHOW_MORE] row opens [group].
     */
    data class Item(
        val key: String,
        val kind: Kind,
        val label: String,
        val note: String? = null,
        val value: String? = null,
        val source: PlaybackSource? = null,
        val group: SourceGroup? = null,
        val chosen: Boolean = false,
    )

    /** Rows an open group shows before "Show N more". */
    const val FIRST_ROWS = 5

    const val BEST_HEADING = "Best for this TV"

    /** The readiness note under the totals. */
    const val READY_NOTE = "Probably on your debrid service unless marked (the add-ons' guess; ✓ played = FirePlayer has played it)"

    const val NOT_ON_RD_NOTE = "Your debrid service has to download these first: they can't play straight away and use a download slot."

    /**
     * The panel's entries for [list] (null: nothing asked yet). [opened] are groups the viewer opened (a closed group's
     * "Show N", or "Show N more" on an open one, which then lists all). [attempts] are this play's tries (the player's
     * panel), [playingKey] the source playing; [time] prints a try's time ("20:41"), [day] a measured date.
     */
    fun items(
        list: SourceList?,
        now: Long,
        opened: Set<SourceGroup>,
        attempts: List<Attempt> = emptyList(),
        playingKey: String? = null,
        time: (Long) -> String = { "" },
        day: (Long) -> String = { "" },
    ): List<Item> {
        val out = ArrayList<Item>()
        if (list == null) {
            out += Item("note:looking", Kind.NOTE, "Looking for sources…")
            return out
        }
        val status = Addon.entries.mapNotNull { a -> list.states[a]?.let { PlayRules.stateText(a, it, now) } }
        out += Item("note:status", Kind.NOTE, status.joinToString(" · ").ifEmpty { PlayRules.NO_ADDONS_LINE })
        if (list.sources.isEmpty()) {
            out += Item("note:none", Kind.NOTE, if (list.complete) "No sources found." else "Looking for sources…")
            return out
        }
        val both = list.sources.count { it.bothAddons }
        val totals = buildString {
            append("${list.sources.size} source${if (list.sources.size == 1) "" else "s"}")
            if (both > 0) append(" · $both offered by more than one add-on")
        }
        out += Item("note:totals", Kind.NOTE, totals)
        out += Item("note:ready", Kind.NOTE, READY_NOTE)

        val tried = attempts.associateBy({ it.source.key }, { it })
        fun row(s: PlaybackSource, group: String, state: String?): Item {
            val tryText = tried[s.key]?.let { a -> "Tried ${time(a.at)}" + if (a.failure != null) " · didn't play" else "" }
            val first = when {
                s.key == playingKey -> "Playing"
                tryText != null -> tryText
                else -> state
            }
            val value = listOfNotNull(first, PlayRules.factsFrom(s, day)).joinToString("\n")
            return Item("src:$group:${s.key}", Kind.SOURCE, PlayRules.badges(s).ifEmpty { s.offers.firstOrNull()?.text.orEmpty() },
                PlayRules.facts(s).ifEmpty { null }, value, s, chosen = s.key == playingKey)
        }

        // Best for this TV: the best, and Last used when it's another row.
        val best = list.best
        val last = list.lastUsed?.takeIf { it.key != best?.key }
        if (best != null || last != null) {
            out += Item("head:best", Kind.HEADING, BEST_HEADING)
            if (last != null) out += row(last, "best", "Last used")
            if (best != null) out += row(best, "best", if (list.complete) "Best" else "Best so far")
        }
        for (g in SourceGroup.entries) {
            val inGroup = list.inGroup(g)
            if (inGroup.isEmpty()) continue
            out += Item("head:${g.name}", Kind.HEADING, "${g.label} · ${inGroup.size}", group = g)
            if (g == SourceGroup.NOT_ON_RD) out += Item("note:${g.name}", Kind.NOTE, NOT_ON_RD_NOTE, group = g)
            val shown = when {
                g in opened -> inGroup.size
                g.startsOpen -> minOf(FIRST_ROWS, inGroup.size)
                else -> 0
            }
            for (s in inGroup.take(shown)) out += row(s, g.name, null)
            val left = inGroup.size - shown
            if (left > 0) {
                val words = if (shown == 0) "Show $left" else "Show $left more"
                out += Item("more:${g.name}", Kind.SHOW_MORE, words, group = g)
            }
        }
        return out
    }

    /** Whether OK on [s] first asks before Real-Debrid fetches it ([Readiness.NOT_READY]). */
    fun asksFirst(s: PlaybackSource): Boolean = s.readiness == Readiness.NOT_READY

    /**
     * Details for a source (no links): its facts, the add-ons' own words per add-on, and how ready it is. Lines, the
     * first a heading-like summary.
     */
    fun details(s: PlaybackSource, day: (Long) -> String): List<Pair<String, String>> {
        val f = s.facts
        val out = ArrayList<Pair<String, String>>()
        out += "Picture" to (listOfNotNull(PlayRules.resolution(f.height), f.hdr.joinToString(" ") { it.label }.ifEmpty { null }, f.codec,
            f.bitDepth?.let { "$it-bit" }).joinToString(" · ").ifEmpty { "Not stated" })
        out += "Sound" to (listOfNotNull(f.audio, f.channels).joinToString(" ").ifEmpty { "Not stated" })
        f.sizeBytes?.let { out += "Size" to PlayRules.size(it) }
        f.avgMbps?.let { out += "Data rate" to "≈${it.toInt()} Mb/s (average, from size)" }
        f.origin?.let { out += "Source" to it }
        f.group?.let { out += "Release group" to it }
        if (f.languages.isNotEmpty()) out += "Languages" to f.languages.joinToString(", ") { it.uppercase() }
        f.seeders?.let { out += "Seeders" to "$it" }
        out += "Debrid" to when (s.readiness) {
            Readiness.PROBABLY_READY -> "Probably on your debrid service (the add-on's guess)"
            Readiness.NOT_READY -> "Not on your debrid service yet: it has to download it first"
            Readiness.UNKNOWN -> "Not said"
        }
        out += "Offered by" to PlayRules.addonsOf(s) + if (s.copies > 1) " · ×${s.copies} copies" else ""
        out += "Facts" to PlayRules.factsFrom(s, day)
        s.warning?.let { out += "Warning" to it }
        for (o in s.offers) out += "${o.addon.label} says" to listOfNotNull(o.text, o.filename).distinct().joinToString("\n")
        return out
    }
}
