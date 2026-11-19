package app.fireplayer.player

import android.app.ActivityManager
import android.content.Context

/**
 * How much memory the TV has, in the steps the film's buffer and the skip preview are sized by. It's read from the
 * TV's total memory and whether Fire OS calls it a low-memory device, not from the app's heap limit: Fire OS gives
 * FirePlayer the same 384 MB heap limit on a Fire TV Stick with 1.7 GB in all as on a TV with 3 GB, though the Stick
 * can't spare it (seen: the Stick's low-memory killer looping while a 4K film held 230 MB of buffer, and the film
 * losing frames).
 */
enum class RamTier(
    /** The most the film's buffer holds, ahead and behind together (MiB). */
    val budgetMiB: Int,
    /** How much of the film is kept behind where it plays (ms), so going back a little plays at once. */
    val backBufferMs: Int,
    /**
     * The most memory what's kept behind may take (MiB; 0: no limit but [backBufferMs]). In a 4K remux at 84 Mb/s, 6 s
     * behind are 63 MB of a 128 MiB buffer, which then holds only 6 s ahead (see [BackBuffer]).
     */
    val backCapMiB: Int,
) {
    /** Fire OS's low-memory devices, and anything under 1.4 GiB. */
    TINY(64, FilmMemory.LEAST_BACK_BUFFER_MS, 16),
    /** Under 1.8 GiB, as the Fire TV Stick 4K Max (1.7 GB). */
    SMALL(128, 6_000, 24),
    /** Under 2.6 GiB: TVs sold as "2 GB". */
    MEDIUM(176, 10_000, 48),
    /** 2.6 GiB and up. */
    LARGE(256, 15_000, 0),
    ;

    /**
     * Short of memory for a second 4K picture: the skip preview keeps to pictures up to 1080p (a 4K-only film gets
     * none) and lets its player go 3 s after skipping stops instead of 15 s.
     */
    val short: Boolean get() = this != LARGE

    /** How long after the last skip the preview's player is kept, in case skipping starts again. */
    val previewHoldMs: Long get() = if (short) 3_000L else 15_000L

    companion object {
        private const val GIB = 1L shl 30

        /** The tier for a TV with [totalMemBytes] of memory in all; [lowRam] is Fire OS's isLowRamDevice. */
        fun of(totalMemBytes: Long, lowRam: Boolean): RamTier = when {
            lowRam -> TINY
            // Not reported (never seen): the largest tier, still held to the heap limit (FilmMemory.plan).
            totalMemBytes <= 0L -> LARGE
            totalMemBytes < (1.4 * GIB).toLong() -> TINY
            totalMemBytes < (1.8 * GIB).toLong() -> SMALL
            totalMemBytes < (2.6 * GIB).toLong() -> MEDIUM
            else -> LARGE
        }
    }
}

/**
 * What the film's buffer gets on this TV (see [FilmMemory.plan]): [budgetBytes] in all, [backBufferMs] of it behind
 * where the film plays, held to [backCapBytes] (0: no limit). [byFreeMemory]: the budget is below the tier's because
 * little memory was free when the film started.
 */
data class FilmBufferPlan(
    val tier: RamTier,
    val budgetBytes: Int,
    val backBufferMs: Int,
    val backCapBytes: Long = 0L,
    val byFreeMemory: Boolean = false,
)

/**
 * The film's buffer and the TV's memory: how big the buffer is on this TV, how far it steps down while Fire OS is short
 * of memory, and when it steps back up.
 */
object FilmMemory {
    /** No film buffer is smaller than this, squeezed or not. */
    const val FLOOR_BYTES = 48 shl 20
    /** Nor bigger than this, whatever the TV. */
    const val CEILING_BYTES = 400 shl 20
    /** The most of the app's heap limit the film's buffer may take: the rest is the app's own. */
    const val HEAP_SHARE = 0.6
    /** The least any TV keeps behind where the film plays. */
    const val LEAST_BACK_BUFFER_MS = 4_000
    /**
     * A squeezed buffer never stops loading with less than this ahead: what's kept behind can't be let go while the film
     * plays, so a cap lowered under it would leave nothing ahead and stall the film.
     */
    const val MIN_AHEAD_US = 5_000_000L

    /**
     * What is left free for everything but the film's buffer when the buffer is sized by the memory free at its start:
     * the decoder's own frames (5 to 8 frames of 4K 10-bit, 121 to 198 MB measured on a Fire TV Stick 4K Max) and room
     * for the rest of the TV.
     */
    const val RESERVE_BYTES = 260L shl 20
    /** Sized by free memory, the buffer still gets this much (it steps down further only under pressure, [capFor]). */
    const val FREE_FLOOR_BYTES = 64 shl 20

    /**
     * The film's buffer on a TV of [tier] whose app heap limit is [maxHeapBytes] (Runtime.maxMemory): the tier's
     * budget, but never more than [HEAP_SHARE] of the heap limit or [CEILING_BYTES], nor less than [FLOOR_BYTES].
     * Held under the tier's budget by a small heap limit, it keeps behind what the tier that budget fits keeps, or
     * the room ahead would shrink to a few seconds in a 4K film.
     *
     * [freeBytes] is the memory the TV has free as the film starts (ActivityManager's availMem; 0 or less: not known).
     * The buffer never takes more of it than leaves [RESERVE_BYTES], down to [FREE_FLOOR_BYTES]: the tier says what the
     * TV can spare at best, the free memory what it can spare now (seen: a 260 MB buffer and up to 198 MB of decoder
     * frames on a 1.7 GB Stick, with 76 kB of swap left).
     */
    fun plan(tier: RamTier, maxHeapBytes: Long, freeBytes: Long = 0L): FilmBufferPlan {
        val byTier = minOf(tier.budgetMiB.toLong() shl 20, (maxHeapBytes * HEAP_SHARE).toLong(), CEILING_BYTES.toLong())
            .coerceAtLeast(FLOOR_BYTES.toLong())
        val byFree = if (freeBytes > 0L) (freeBytes - RESERVE_BYTES).coerceAtLeast(minOf(FREE_FLOOR_BYTES.toLong(), byTier)) else byTier
        val budget = minOf(byTier, byFree).toInt()
        val fits = RamTier.entries.lastOrNull { (it.budgetMiB.toLong() shl 20) <= budget } ?: RamTier.TINY
        return FilmBufferPlan(
            tier, budget, minOf(tier.backBufferMs, fits.backBufferMs),
            // What's kept behind is held to the smaller of the two tiers' sizes, as its length is.
            backCapBytes = listOf(tier.backCapMiB, fits.backCapMiB).filter { it > 0 }.minOrNull()?.let { it.toLong() shl 20 } ?: 0L,
            byFreeMemory = budget < byTier,
        )
    }

    /**
     * What the buffer fills to at squeeze [step]: 0 is the whole [budgetBytes], 1 three quarters (Fire OS says memory
     * is running low), 2 half (critical, or still low half a minute on); never below [FLOOR_BYTES].
     */
    fun capFor(budgetBytes: Int, step: Int): Int {
        val cap = when {
            step <= 0 -> budgetBytes
            step == 1 -> (budgetBytes.toLong() * 3 / 4).toInt()
            else -> budgetBytes / 2
        }
        return cap.coerceAtLeast(FLOOR_BYTES).coerceAtMost(budgetBytes)
    }

    /**
     * How full the buffer may get before loading stops, with [aheadUs] loaded ahead: [capBytes], or the whole
     * [budgetBytes] while less than [MIN_AHEAD_US] is ahead.
     */
    fun fillLimit(capBytes: Int, budgetBytes: Int, aheadUs: Long): Int =
        if (aheadUs >= MIN_AHEAD_US) capBytes else maxOf(capBytes, budgetBytes)

    @Volatile private var tier: RamTier? = null

    /** This TV's tier, read once (its total memory doesn't change). */
    fun tier(context: Context): RamTier = tier ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val found = if (am == null) RamTier.LARGE else {
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            RamTier.of(info.totalMem, am.isLowRamDevice)
        }
        tier = found
        found
    }

    /** The memory the TV has free right now, in bytes (ActivityManager.MemoryInfo.availMem); 0 when it can't be read. */
    fun freeNow(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem }.getOrDefault(0L)
    }

    /** Whether Fire OS says memory is low right now (ActivityManager.MemoryInfo.lowMemory). */
    fun lowNow(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }
}

/**
 * How far the film's buffer is squeezed for the TV's memory ([step]: 0 not at all, 1 to three quarters, 2 to half; see
 * [FilmMemory.capFor]). Fire OS asking the app to trim (onTrimMemory) goes straight to the step it calls for; its
 * "memory is low", read every 10 s, means step 1, and step 2 when still low [escalateMs] later. After [recoverMs]
 * without either, it steps back up one step, and another after the same again. Times are elapsedRealtime.
 */
class MemoryPressure(
    private val escalateMs: Long = 30_000L,
    private val recoverMs: Long = 120_000L,
) {
    var step = 0
        private set
    // When the latest run of "memory is low" readings began, or -1 when the last reading wasn't low.
    private var lowSince = -1L
    // When memory was last short (a trim asked for, or a low reading), or when the last step back up was taken.
    private var lastPressureAt = 0L

    /** Fire OS asked the app to trim, calling for [wanted] (1 running low, 2 critical). True when the step changed. */
    fun trimAsked(wanted: Int, nowMs: Long): Boolean {
        lastPressureAt = nowMs
        return raise(wanted)
    }

    /** The 10 s check: [low] is whether Fire OS says memory is low now. True when the step changed. */
    fun checked(low: Boolean, nowMs: Long): Boolean {
        if (low) {
            if (lowSince < 0) lowSince = nowMs
            lastPressureAt = nowMs
            return raise(if (nowMs - lowSince >= escalateMs) 2 else 1)
        }
        lowSince = -1L
        if (step == 0 || nowMs - lastPressureAt < recoverMs) return false
        step--
        lastPressureAt = nowMs
        return true
    }

    private fun raise(wanted: Int): Boolean {
        val next = wanted.coerceIn(0, 2)
        if (next <= step) return false
        step = next
        return true
    }
}
