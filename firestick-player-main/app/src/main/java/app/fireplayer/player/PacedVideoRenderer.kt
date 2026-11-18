package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * Media3's video renderer, showing each frame on its own refresh of a TV switched to the film's rate.
 *
 * Where Media3 can't line frames up with the TV's refreshes (see [VsyncFix]), it sends each one out at the time the
 * film gives it. Matroska gives whole milliseconds, so frames of a 24 fps film are 41 or 42 ms apart; when the TV's
 * refreshes fall close to those times, that half millisecond tips frames onto the refresh before or after, and pairs
 * of frames land on one refresh while the next shows nothing (one interval in ten on a 4K film, measured on a Fire TV
 * Stick 4K Plus). Here the TV's refreshes are timed from when frames actually reached the screen, and each frame goes
 * to the refresh after the one before; the film's own times only move that on by a refresh when the two drift more
 * than [FramePacer.SLACK] of one apart, as they must when the film's rate isn't quite the TV's. When the screen's times
 * don't fit the refresh measured, frames go out on Media3's times and [VsyncFix] measures again, until they fit again
 * (see [FramePacer]).
 *
 * It also counts the frames lost that Media3 doesn't ([counts]): frames the pacer held a refresh longer or put on the
 * refresh of the frame before (that one isn't sent at all: the screen would only have shown it in place of the frame
 * before), and frames the decoder never gave ([FrameGaps]).
 */
@OptIn(UnstableApi::class)
class PacedVideoRenderer(
    builder: Builder,
    private val vsync: VsyncFix,
    private val counts: FrameCounts,
) : MediaCodecVideoRenderer(builder) {

    // On the playback thread.
    private val pacer = FramePacer()
    private val gaps = FrameGaps()

    private val rendered = MediaCodecAdapter.OnFrameRenderedListener { _, _, nanoTime ->
        val periodNs = vsync.tvPeriodNs
        val wasOff = pacer.offGridPeriodNs != 0L
        if (pacer.rendered(nanoTime, vsync.unaligned, periodNs, vsync.modes)) {
            Log.i(TAG, "Frames reached the screen between the TV's refreshes as measured (every ${periodNs / 1000} us): " +
                "Media3 times them until it's measured again")
            vsync.screenDisagrees()
        } else if (wasOff && pacer.offGridPeriodNs == 0L) {
            Log.i(TAG, "Frames reach the screen on the TV's refreshes as measured again (every ${periodNs / 1000} us): " +
                "each on its own refresh again")
        }
    }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        pacer.reset()
        gaps.reset()
        // Tunnelled playback has Media3's own listener, and Media3 its own timing; the film never plays tunnelled here.
        if (getConfiguration().tunneling) return
        val looper = Looper.myLooper() ?: return
        try {
            codec?.setOnFrameRenderedListener(rendered, Handler(looper))
        } catch (e: RuntimeException) {
            // Without the screen's times frames go out as Media3 sends them
        }
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        super.onOutputFormatChanged(format, mediaFormat)
        // A frame's length as the film states it, to tell a gap in its times by.
        gaps.statedFrameUs = if (format.frameRate > 0f) (1_000_000.0 / format.frameRate).toLong() else 0L
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        // A jump: the frames after it don't follow on from those before.
        gaps.reset()
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        val decoded = decoderCounters
        val missing = gaps.frame(presentationTimeUs, decoded.droppedBufferCount, decoded.skippedOutputBufferCount)
        if (missing > 0) counts.neverDecoded += missing
        val at = paced(releaseTimeNs)
        when (pacer.move) {
            FramePacer.SAME -> {
                // It would only take the place of the frame before on the screen: given back to the decoder at once
                // instead, and counted by Media3 as a frame skipped.
                counts.sameRefresh++
                skipOutputBuffer(codec, index, presentationTimeUs)
                gaps.skippedHere()
                return
            }
            FramePacer.LATER -> counts.shownTwice++
            // A refresh further on with no frame missing before it: the frame before held for an extra refresh.
            FramePacer.FOLLOWED -> if (gaps.between == 0) counts.shownTwice++
        }
        super.renderOutputBufferV21(codec, index, presentationTimeUs, at)
    }

    private fun paced(releaseTimeNs: Long): Long = pacer.paced(releaseTimeNs, vsync.unaligned, vsync.tvPeriodNs, vsync.modes)

    private companion object {
        const val TAG = "FirePlayer"
    }
}
