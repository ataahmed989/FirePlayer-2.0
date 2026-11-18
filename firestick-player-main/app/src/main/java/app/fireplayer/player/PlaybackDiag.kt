package app.fireplayer.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the film player does to its frames and the Fire TV's memory, in the log under one tag, "FirePlayerDiag", so a
 * choppy film can be told apart afterwards: Media3 dropping late frames, the pacer holding or skipping frames, the
 * decoder never giving them, the film's memory, the system short of memory, or heat.
 *
 * - Events, one line each: the video decoder chosen (and released), the picture's and the sound's format, the film
 *   stopping to load and going on, Media3's batches of dropped frames.
 * - Once every [LOG_EVERY_S] seconds while the film plays, one line: the frames shown and lost in those seconds and over
 *   the last minute ([FrameTally]), how much is loaded ahead and the memory it holds, the picture, the TV's refresh,
 *   the app's memory (Java heap, native heap, graphics), the system's free memory, and the heat.
 *
 * Never a link, a name or a key (links can carry the viewer's Real-Debrid key): only counts, sizes, formats and times.
 *
 * [second] comes from the player screen's tick once a second, on the main thread. The memory and heat are read on a
 * thread of its own, as reading the app's memory takes a while.
 */
@OptIn(UnstableApi::class)
class PlaybackDiag(context: Context) : AnalyticsListener {

    private val app = context.applicationContext

    /** Given to the film's video renderer, which counts the frames lost that Media3 doesn't ([PlayerFactory.build]). */
    val counts = FrameCounts()

    /** The last minute's frames, kept from [second]. */
    val tally = FrameTally()

    // On the main thread.
    private var counters: DecoderCounters? = null
    private var seconds = 0
    private val sinceLog = LongArray(5)
    private var readyOnce = false
    private var waitingSince = 0L
    private var jumpedAt = 0L

    private val busy = AtomicBoolean(false)
    private var reader: ExecutorService? = null

    /** A new player: its first wait to load isn't a stall. */
    fun newPlayer() {
        readyOnce = false
        waitingSince = 0L
        counters = null
    }

    /**
     * Once a second: the frames so far into [tally]; every [LOG_EVERY_S] seconds while [player] plays, the line.
     * [filmBytes] is the memory the film's loaded part holds, [refreshHz] the TV's refresh rate, [hdrScreen] whether
     * the TV reports HDR.
     */
    fun second(player: ExoPlayer, filmBytes: Long, refreshHz: Float, hdrScreen: Boolean) {
        val c = player.videoDecoderCounters
        if (c != null) {
            c.ensureUpdated()
            val fresh = c !== counters
            counters = c
            tally.sample(
                SystemClock.elapsedRealtime(), c.droppedBufferCount.toLong(), c.renderedOutputBufferCount.toLong(),
                counts.shownTwice, counts.sameRefresh, counts.neverDecoded, fresh,
            )
        } else {
            // No picture being decoded: only the pacer's and gaps' counts move on (they don't).
            tally.sample(SystemClock.elapsedRealtime(), lastDropped(), lastRendered(), counts.shownTwice, counts.sameRefresh,
                counts.neverDecoded)
        }
        for (k in sinceLog.indices) sinceLog[k] += tally.added(k)
        if (++seconds % LOG_EVERY_S != 0) return
        val playing = player.isPlaying
        if (!playing || !Log.isLoggable(TAG, Log.INFO)) {
            sinceLog.fill(0)
            return
        }
        val line = StringBuilder(400)
            .append("play pos=").append(player.currentPosition / 1000).append('s')
            .append(" shown=").append(sinceLog[FrameTally.RENDERED])
            .append(" dropped=").append(sinceLog[FrameTally.DROPPED])
            .append(" twice=").append(sinceLog[FrameTally.TWICE])
            .append(" same=").append(sinceLog[FrameTally.SAME])
            .append(" never=").append(sinceLog[FrameTally.NEVER])
            .append(" min[shown=").append(tally.minute(FrameTally.RENDERED))
            .append(" dropped=").append(tally.minute(FrameTally.DROPPED))
            .append(" twice=").append(tally.minute(FrameTally.TWICE))
            .append(" same=").append(tally.minute(FrameTally.SAME))
            .append(" never=").append(tally.minute(FrameTally.NEVER)).append(']')
            .append(" ahead=").append(player.totalBufferedDuration).append("ms")
            .append(" buf=").append(mb(filmBytes))
            .append(' ').append(video(player.videoFormat))
            .append(" tv=").append(String.format(Locale.US, "%.3fHz", refreshHz))
            .append(" tvHdr=").append(if (hdrScreen) 1 else 0)
        sinceLog.fill(0)
        // The rest is read on its own thread; one at a time, a line left out if the one before hasn't finished.
        if (!busy.compareAndSet(false, true)) return
        val pool = reader ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "FirePlayerDiag").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.also { reader = it }
        try {
            pool.execute {
                try {
                    Log.i(TAG, line.append(' ').append(system()).toString())
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: RuntimeException) {
            busy.set(false)
        }
    }

    private fun lastDropped(): Long = counters?.droppedBufferCount?.toLong() ?: 0L
    private fun lastRendered(): Long = counters?.renderedOutputBufferCount?.toLong() ?: 0L

    /** The app's memory, the system's, and the heat: "java=270MB native=60MB gfx=118MB pss=448MB avail=412MB low=0 heat=0". */
    private fun system(): String {
        val out = StringBuilder(160)
        runCatching {
            val m = Debug.MemoryInfo()
            Debug.getMemoryInfo(m)
            var javaKb = m.dalvikPss.toLong()
            var nativeKb = m.nativePss.toLong()
            var graphicsKb = -1L
            if (Build.VERSION.SDK_INT >= 23) {
                m.getMemoryStat("summary.java-heap")?.toLongOrNull()?.let { javaKb = it }
                m.getMemoryStat("summary.native-heap")?.toLongOrNull()?.let { nativeKb = it }
                m.getMemoryStat("summary.graphics")?.toLongOrNull()?.let { graphicsKb = it }
            }
            out.append("java=").append(mb(javaKb * 1024)).append(" native=").append(mb(nativeKb * 1024))
            if (graphicsKb >= 0) out.append(" gfx=").append(mb(graphicsKb * 1024))
            out.append(" pss=").append(mb(m.totalPss.toLong() * 1024))
        }
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            out.append(" avail=").append(mb(mi.availMem)).append(" low=").append(if (mi.lowMemory) 1 else 0)
        }
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 29) out.append(" heat=").append(pm.currentThermalStatus)
            if (Build.VERSION.SDK_INT >= 30) {
                val headroom = pm.getThermalHeadroom(HEADROOM_AHEAD_S)
                if (!headroom.isNaN()) out.append(" headroom=").append(String.format(Locale.US, "%.2f", headroom))
            }
        }
        return out.toString().trim()
    }

    /** The reader's thread is let go: with the player screen. */
    fun release() {
        reader?.shutdownNow()
        reader = null
    }

    // --- Events

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(TAG, "decoder video $decoderName started in ${initializationDurationMs}ms")
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        Log.i(TAG, "decoder video $decoderName released")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "format " + video(format) + (format.bitrate.takeIf { it > 0 }?.let { " bitrate=${it / 1000}kb/s" } ?: ""))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "format audio=${format.sampleMimeType} channels=${format.channelCount} rate=${format.sampleRate}" +
            (format.codecs?.let { " codecs=$it" } ?: ""))
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.i(TAG, "drops $droppedFrames in ${elapsedMs}ms")
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) jumpedAt = SystemClock.elapsedRealtime()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> if (readyOnce && waitingSince == 0L) {
                waitingSince = now
                Log.i(TAG, "rebuffer start pos=${eventTime.currentPlaybackPositionMs / 1000}s " +
                    "ahead=${eventTime.totalBufferedDurationMs}ms" + if (now - jumpedAt < AFTER_JUMP_MS) " after a jump" else "")
            }
            Player.STATE_READY -> {
                if (waitingSince > 0L) Log.i(TAG, "rebuffer end after ${now - waitingSince}ms")
                waitingSince = 0L
                readyOnce = true
            }
            else -> waitingSince = 0L
        }
    }

    companion object {
        const val TAG = "FirePlayerDiag"
        /** Seconds between the lines while the film plays. */
        const val LOG_EVERY_S = 10
        private const val HEADROOM_AHEAD_S = 10
        private const val AFTER_JUMP_MS = 3_000L

        private fun mb(bytes: Long): String = String.format(Locale.US, "%.1fMB", bytes / 1_048_576.0)

        /** "video=3840x2160@23.976 mime=video/hevc codecs=hvc1.2.4.L153.B0 hdr=HDR10 bits=10": no ids, no labels. */
        fun video(f: Format?): String {
            if (f == null) return "video=none"
            val spec = MediaSpec.of(f, null)
            val fps = if (f.frameRate > 0f) String.format(Locale.US, "@%.3f", f.frameRate) else ""
            return "video=${f.width}x${f.height}$fps mime=${f.sampleMimeType}" +
                (f.codecs?.let { " codecs=$it" } ?: "") +
                " hdr=${spec.hdr ?: "unstated"}" +
                (spec.bitDepth.takeIf { it > 0 }?.let { " bits=$it" } ?: "")
        }
    }
}
