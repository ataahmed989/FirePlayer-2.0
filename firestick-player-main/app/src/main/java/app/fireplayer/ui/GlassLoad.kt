package app.fireplayer.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import app.fireplayer.player.FilmMemory
import app.fireplayer.player.RamTier

/**
 * How hard the Glass look may work on the film playing. Each copy of the film ([GlassBackdrop], and [BarWatch] in Fill
 * screen or Stretch) makes the TV fetch the frame the decoder just made and convert it on the same graphics chip that
 * puts the film on screen. For a 4K HDR film that's a 3840 x 2160 10-bit frame each time; on a Fire TV Stick 4K Max,
 * with 1.7 GB of memory in all, such a film lost about one frame in six while the Stick was out of memory, with 118 MB
 * of graphics memory in FirePlayer that appeared as the glass Settings window opened. So while such a film plays on a
 * TV with that little memory ("heavy video"), the glass copies it at most [HEAVY_REFRESH_PER_SEC] times a second
 * (fewer if that's the setting) and looks for camera cuts every [HEAVY_PROBE_MS] instead of every [PROBE_MS]. The look
 * itself and the saved setting stay as they are.
 */
object GlassLoad {

    /**
     * How often a probe looks for a camera cut while the film plays: soon enough that the glass changes with the new
     * shot, and cheap, as a probe is only 32 x 18 pixels with no blur.
     */
    const val PROBE_MS = 100L
    /** The same with heavy video. */
    const val HEAVY_PROBE_MS = 500L
    /** The most copies a second with heavy video. */
    const val HEAVY_REFRESH_PER_SEC = 2

    /** 4K: 2000 lines or more (2160p), or 3000 dots across (a wide 4K film is kept as 3840 x 1600). */
    const val UHD_MIN_HEIGHT = 2000
    const val UHD_MIN_WIDTH = 3000

    /** Under 1.8 GiB in all a TV is short of memory for this: the Fire TV Stick 4K Max has 1.7 GB. */
    const val SMALL_RAM_BYTES = 1_932_735_283L // 1.8 * 2^30

    /** The Glass refresh setting ([chosen], copies a second) as it applies now: at most 2 with [heavy] video. */
    fun refreshPerSec(chosen: Int, heavy: Boolean): Int {
        val rate = chosen.coerceIn(1, 8)
        return if (heavy) minOf(rate, HEAVY_REFRESH_PER_SEC) else rate
    }

    fun probeMs(heavy: Boolean): Long = if (heavy) HEAVY_PROBE_MS else PROBE_MS

    /**
     * What the Glass refresh row shows while the setting is lowered for heavy video, such as "8 a second · 2 in 4K HDR";
     * null when it isn't (the row then shows the setting's own name).
     */
    fun refreshLabel(chosen: Int, heavy: Boolean): String? {
        val now = refreshPerSec(chosen, heavy)
        return if (heavy && now < chosen) "$chosen a second · $now in 4K HDR" else null
    }

    /** A TV with [totalMemBytes] in all (0: not known) is short of memory; [lowRam] is Fire OS's isLowRamDevice. */
    fun smallRam(totalMemBytes: Long, lowRam: Boolean): Boolean = lowRam || totalMemBytes in 1 until SMALL_RAM_BYTES

    /**
     * Whether the video is 4K HDR: [width] x [height] is 4K, and it's HDR10 or HDR10+ ([colorTransfer] PQ), HLG, or
     * Dolby Vision ([sampleMime], or [codecs] dvhe, dvh1, dvav, dva1 or dav1).
     */
    fun uhdHdr(width: Int, height: Int, colorTransfer: Int, sampleMime: String?, codecs: String?): Boolean {
        val uhd = height >= UHD_MIN_HEIGHT || width >= UHD_MIN_WIDTH
        val dv = sampleMime == MimeTypes.VIDEO_DOLBY_VISION ||
            codecs != null && DV_CODECS.any { codecs.startsWith(it) }
        val hdr = dv || colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG
        return uhd && hdr
    }

    /**
     * Heavy video: the [video] track playing is 4K HDR on a TV that's [smallRam]. The size shown ([shownWidth] x
     * [shownHeight], 0 while not known) counts when the track doesn't say.
     */
    @OptIn(UnstableApi::class)
    fun heavy(video: Format?, shownWidth: Int, shownHeight: Int, smallRam: Boolean): Boolean {
        if (!smallRam || video == null) return false
        val w = maxOf(video.width, shownWidth)
        val h = maxOf(video.height, shownHeight)
        return uhdHdr(w, h, video.colorInfo?.colorTransfer ?: Format.NO_VALUE, video.sampleMimeType, video.codecs)
    }

    private val DV_CODECS = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /**
     * Whether this TV is short of memory: the same reading the film's buffer is sized by ([FilmMemory.tier], read once),
     * so the glass and the buffer never disagree about the TV.
     */
    fun smallRam(context: Context): Boolean = FilmMemory.tier(context).let { it == RamTier.TINY || it == RamTier.SMALL }
}
