package app.fireplayer.vod.sources

import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.CopyRank
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import kotlin.math.floor
import kotlin.math.ln

/** What "Best for this TV" weighs a list against. */
class RankInput(
    val plan: TvPlan,
    /** [CopyLanguage.wanted]: the sound to look for, best first. */
    val wanted: List<String>,
    /** What FirePlayer measured of a release, by [app.fireplayer.vod.ReleaseKey.storeKey]; null when never played. */
    val measured: (String) -> Measured?,
    val runtimeMin: Int?,
    val episode: Boolean,
)

/**
 * "Best for this TV" (r3_sources-organize §4.4), the same order in every group: plays on this TV (measured here > likely
 * > unknown > likely not), Real-Debrid state (played here > probably ready > unknown), the sound wanted
 * ([CopyLanguage]), the picture ([CopyRank]'s data per pixel with codec efficiency, starved and full limits, and the HDR
 * the [TvPlan] prefers), then width, bit depth, source, sound for this TV's receiver, size. Pure.
 */
object SourceRank {

    /** Above this average a row says "High data rate" (the user's Wi-Fi measured about 55-63 Mb/s). */
    const val HIGH_MBPS = 60.0

    /** A film's or an episode's length when the add-ons' size is known but the runtime isn't (ranking only, never shown). */
    private const val FILM_MIN = 120
    private const val EPISODE_MIN = 45

    fun rank(sources: List<PlaybackSource>, input: RankInput): List<PlaybackSource> {
        val scored = sources.mapIndexed { i, s ->
            val measured = s.release?.let { input.measured(it.storeKey) }
            val (fit, warning) = fit(s.facts, input.plan, measured)
            val ranked = s.copy(fit = fit, warning = warning, measured = measured)
            Scored(ranked, i, keyOf(ranked, input))
        }
        return scored.sortedWith(ORDER).map { it.source }
    }

    /** The automatic pick: the first ranked source that may be picked by itself. */
    fun best(ranked: List<PlaybackSource>): PlaybackSource? = ranked.firstOrNull { it.automatic }

    private class Key(
        val fit: Int,
        val ready: Int,
        val language: Int,
        val picture: Int,
        val height: Int,
        val hdr: Int,
        val bits: Int,
        val origin: Int,
        val sound: Int,
        val size: Long,
        val seeders: Int,
    )

    private class Scored(val source: PlaybackSource, val index: Int, val key: Key)

    private val ORDER: Comparator<Scored> = compareByDescending<Scored> { it.key.fit }
        .thenByDescending { it.key.ready }
        .thenByDescending { it.key.language }
        .thenByDescending { it.key.picture }
        .thenByDescending { it.key.height }
        .thenByDescending { it.key.hdr }
        .thenByDescending { it.key.bits }
        .thenByDescending { it.key.origin }
        .thenByDescending { it.key.sound }
        .thenByDescending { it.key.size }
        .thenByDescending { it.key.seeders }
        .thenBy { it.index }

    private fun keyOf(s: PlaybackSource, input: RankInput): Key {
        val f = s.facts
        val ready = when {
            s.measured?.spec != null && s.measured.health?.troubled != true -> 3
            s.readiness == Readiness.PROBABLY_READY -> 2
            s.readiness == Readiness.UNKNOWN -> 1
            else -> 0
        }
        val hdr = hdrRank(f, input.plan)
        return Key(
            fit = when (s.fit) {
                TvFit.MEASURED_OK -> 3
                TvFit.LIKELY -> 2
                TvFit.UNKNOWN -> 1
                TvFit.LIKELY_NOT -> 0
            },
            ready = ready,
            language = language(f.languages, s.measured?.spec?.audioLangs, input.wanted),
            picture = pictureBucket(f, input, hdr),
            height = f.height ?: 0,
            hdr = hdr,
            bits = f.bitDepth ?: 0,
            origin = originRank(f.origin),
            sound = sound(f, input.plan),
            size = f.sizeBytes ?: 0L,
            seeders = f.seeders ?: 0,
        )
    }

    /** Whether [facts] likely play well under [plan], and at most one warning for the row. */
    fun fit(facts: SourceFacts, plan: TvPlan, measured: Measured?): Pair<TvFit, String?> {
        val dv = Hdr.DOLBY_VISION in facts.hdr
        val tvDv = plan.hdrOrder.any { Hdr.DOLBY_VISION in it }
        val base = Hdr.HDR10 in facts.hdr || Hdr.HDR10_PLUS in facts.hdr
        val avg = facts.avgMbps
        val cap = plan.maxMbps
        return when {
            measured?.health?.troubled == true -> TvFit.LIKELY_NOT to "Buffered here before"
            dv && facts.dvProfile == 7 -> TvFit.LIKELY_NOT to "Dolby Vision 7: may not play here"
            dv && !tvDv && (facts.dvProfile == 5 || (facts.dvProfile == null && !base)) -> TvFit.LIKELY_NOT to "Needs a Dolby Vision TV"
            cap != null && avg != null && avg > cap -> TvFit.LIKELY_NOT to "Over your $cap Mb/s limit"
            facts.height != null && facts.height > plan.topHeight -> TvFit.LIKELY_NOT to null
            measured?.spec != null -> TvFit.MEASURED_OK to highRate(avg)
            facts.height == null -> TvFit.UNKNOWN to highRate(avg)
            else -> TvFit.LIKELY to highRate(avg)
        }
    }

    private fun highRate(avg: Double?) = if (avg != null && avg > HIGH_MBPS) "High data rate" else null

    /**
     * How well the source's HDR suits the plan: the plan's first tier highest. Dolby Vision with an HDR10 base (or
     * profile 8) counts as HDR10 on a TV without Dolby Vision. On a TV showing no HDR, SDR ranks first.
     */
    fun hdrRank(facts: SourceFacts, plan: TvPlan): Int {
        val tiers = plan.hdrOrder
        val kinds = HashSet(facts.hdr)
        val tvDv = tiers.any { Hdr.DOLBY_VISION in it }
        if (Hdr.DOLBY_VISION in kinds && !tvDv && (facts.dvProfile == 8 || Hdr.HDR10 in kinds || Hdr.HDR10_PLUS in kinds)) {
            kinds += Hdr.HDR10
        }
        if (tiers.isEmpty()) return if (facts.hdr.isEmpty()) 2 else 1
        if (facts.hdr.isEmpty()) return 1
        val i = tiers.indexOfFirst { tier -> tier.any { it in kinds } }
        return if (i < 0) 0 else tiers.size - i + 1
    }

    /** The picture's data for its frame, in 15 % steps ([CopyRank.CLOSE]): close figures tie and the next keys decide. */
    private fun pictureBucket(f: SourceFacts, input: RankInput, hdr: Int): Int {
        val bps = bitsPerSecond(f, input) ?: return 0
        val height = f.height ?: 0
        val codec = f.codec ?: if (height >= 2160) "HEVC" else null
        val spec = MediaSpec(videoCodec = codec, width = widthOf(height), height = height, bitDepth = f.bitDepth ?: 0, overallBps = bps)
        var score = bps * (CopyRank.EFFICIENCY[codec] ?: 1.0)
        CopyRank.perPixel(spec, bps)?.let { each -> if (each > CopyRank.FULL) score = score * CopyRank.FULL / each }
        if (CopyRank.starved(spec, bits = bps)) score *= CopyRank.FED_SHARE
        // HDR the TV prefers wins when it has about as much data (CopyRank.HDR_FLOOR); on an SDR TV, SDR does.
        val top = if (input.plan.hdrOrder.isEmpty()) 2 else input.plan.hdrOrder.size + 1
        score *= when {
            hdr >= top -> 1 / CopyRank.HDR_FLOOR
            hdr >= 2 -> 1 / 0.85
            hdr == 0 -> 0.85
            else -> 1.0
        }
        if (score <= 1.0) return 0
        return floor(ln(score) / ln(1 + CopyRank.CLOSE)).toInt()
    }

    /** The average data rate, from size and runtime; with no runtime, an assumed length (ranking only). */
    private fun bitsPerSecond(f: SourceFacts, input: RankInput): Long? {
        f.avgMbps?.let { return (it * 1_000_000).toLong() }
        val size = f.sizeBytes ?: return null
        val minutes = input.runtimeMin ?: if (input.episode) EPISODE_MIN else FILM_MIN
        return (size * 8.0 / (minutes * 60.0)).toLong()
    }

    fun widthOf(height: Int): Int = when {
        height >= 2160 -> 3840
        height >= 1440 -> 2560
        height >= 1080 -> 1920
        height >= 720 -> 1280
        height > 0 -> height * 16 / 9
        else -> 0
    }

    private fun originRank(origin: String?): Int = when (origin) {
        "REMUX" -> 6
        "BluRay" -> 5
        "WEB-DL", "WEB" -> 4
        "WEBRip" -> 3
        "HDTV" -> 2
        "DVD" -> 1
        else -> 0
    }

    /** Lossless over lossy over stereo codecs ([CopyRank.soundRank]), a little more when the receiver takes it as it is. */
    private fun sound(f: SourceFacts, plan: TvPlan): Int {
        val audio = f.audio ?: return 0
        val atmos = audio.contains("Atmos")
        val codec = audio.removeSuffix(" Atmos").takeUnless { it == "Atmos" }
        val base = CopyRank.soundRank(MediaSpec(audioCodec = codec ?: if (atmos) "DD+" else null, atmos = atmos))
        val format = when (codec) {
            "TrueHD" -> SoundFormat.TRUEHD
            "DTS-HD MA", "DTS:X", "DTS-HD" -> SoundFormat.DTS_HD
            "DTS" -> SoundFormat.DTS
            "DD+" -> if (atmos) SoundFormat.EAC3_JOC else SoundFormat.EAC3
            "DD" -> SoundFormat.AC3
            else -> null
        }
        val through = if (format != null && format in plan.sound) 1 else 0
        val channels = f.channels?.substringBefore('.')?.toIntOrNull() ?: 0
        return base * 20 + through * 10 + channels
    }

    /** 3 has the first language wanted, 2 the second; 1 may have one (several, or not stated); 0 known to have neither. */
    fun language(languages: List<String>, measuredLangs: String?, wanted: List<String>): Int {
        if (!measuredLangs.isNullOrEmpty()) return CopyLanguage.fit(wanted, measuredLangs, null)
        val langs = languages.filter { it != Words.MULTI }
        if (langs.isEmpty()) return 1
        if (wanted.isNotEmpty() && wanted[0] in langs) return 3
        if (wanted.size > 1 && wanted[1] in langs) return 2
        return if (Words.MULTI in languages) 1 else 0
    }

    // ---- The same kind of source (next episode, §6.5) and the closest match (a Continue card's file gone) ----

    /**
     * How alike [a] is to [like]: the same binge group (Torrentio's release group of episodes) first, then the same
     * add-on, resolution, HDR kinds, codec, audio and size class.
     */
    fun likeness(a: PlaybackSource, like: PlaybackSource): Int {
        var score = 0
        val groups = like.offers.mapNotNull { it.bingeGroup }.toSet()
        if (a.offers.any { it.bingeGroup != null && it.bingeGroup in groups }) score += 100
        if (a.addons.any { it in like.addons }) score += 16
        if (a.facts.height != null && a.facts.height == like.facts.height) score += 8
        if (a.facts.hdr == like.facts.hdr) score += 4
        if (a.facts.codec != null && a.facts.codec == like.facts.codec) score += 2
        if (a.facts.audio != null && a.facts.audio == like.facts.audio) score += 1
        val x = a.facts.avgMbps ?: a.facts.sizeBytes?.toDouble()
        val y = like.facts.avgMbps ?: like.facts.sizeBytes?.toDouble()
        if (x != null && y != null && x > 0 && y > 0 && x / y in 0.66..1.5) score += 1
        return score
    }

    /** Same add-on and same resolution at least: below this a source isn't "the same kind". */
    const val SAME_KIND = 24

    /**
     * The source most like [like] among [candidates] (ranked, best first), or null when none is the same kind (then
     * the caller takes the best).
     */
    fun mostLike(candidates: List<PlaybackSource>, like: PlaybackSource): PlaybackSource? {
        var best: PlaybackSource? = null
        var score = SAME_KIND - 1
        for (c in candidates) {
            val s = likeness(c, like)
            if (s > score) {
                best = c
                score = s
            }
        }
        return best
    }
}
