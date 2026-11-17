package app.fireplayer.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.fireplayer.data.Prefs
import app.fireplayer.player.PlayerFactory

/**
 * The app's in-process live player: one [ExoPlayer] for the live channel, shown on whichever [SurfaceView] the
 * screen hands it (the guide's miniature and full screen are the same surface resized, so neither switch touches
 * the stream). Lives for the process; the screen tells it when it starts and stops ([onScreenStarted] /
 * [onScreenStopped]) so no stream stays open in the background.
 *
 * Everything here runs on the main thread; Media3 does its loading and decoding on its own playback threads.
 * VOD keeps its own player in PlayerActivity, untouched.
 */
@OptIn(UnstableApi::class)
class PlayerHost private constructor(context: Context) {

    /** What the screen shows about live playback. */
    data class Status(
        val key: String?,
        val buffering: Boolean,
        val playing: Boolean,
        /** Shown when the channel can't be played (after retries); null while all is well. */
        val error: String?,
        val video: Format?,
        val audio: Format?,
        val videoSize: VideoSize,
        val captions: List<Track>,
        val audioTracks: List<Track>,
    )

    data class Track(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

    interface Listener {
        fun onLiveStatus(status: Status)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var player: ExoPlayer? = null
    private var builtHeaders: Map<String, String>? = null
    private var surface: SurfaceView? = null
    private var retries = 0
    private var error: String? = null

    private val engine = object : LiveSession.Engine {
        override fun open(source: LiveSession.Source) = openStream(source)
        override fun close() = closeStream()
    }

    val session = LiveSession(engine, budget)

    val playingKey: String? get() = session.playingKey
    val currentKey: String? get() = session.source?.key

    fun addListener(l: Listener) {
        listeners += l
        l.onLiveStatus(status())
    }

    fun removeListener(l: Listener) { listeners -= l }

    /** Plays [source] (or keeps playing it when it already is). */
    fun tune(source: LiveSession.Source): LiveSession.Tune {
        val result = session.tune(source)
        publish()
        return result
    }

    /** Shows live video on [view] (the guide's surface). Safe to call again with the same view. */
    fun attach(view: SurfaceView) {
        if (surface === view) return
        surface?.let { player?.clearVideoSurfaceView(it) }
        surface = view
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        if (surface !== view) return
        player?.clearVideoSurfaceView(view)
        surface = null
    }

    fun onScreenStarted(): Boolean {
        val resumed = session.resume()
        publish()
        return resumed
    }

    fun onScreenStopped() {
        session.suspend()
        publish()
    }

    /** The viewer closed live playback, or a film is starting. */
    fun stop() {
        session.stop()
        publish()
    }

    fun setMuted(muted: Boolean) { player?.volume = if (muted) 0f else 1f }

    fun selectCaption(track: Track?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (track == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(track.groupIndex) ?: return
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
        }
        p.trackSelectionParameters = b.build()
    }

    fun selectAudio(track: Track) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(track.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
            .build()
    }

    // ---- The stream ----------------------------------------------------------------------------------------------

    private fun openStream(source: LiveSession.Source) {
        main.removeCallbacks(retry)
        retries = 0
        error = null
        val p = playerFor(source.headers)
        p.setMediaItem(mediaItem(source))
        p.prepare()
        p.playWhenReady = true
    }

    private fun closeStream() {
        main.removeCallbacks(retry)
        // Released, not just stopped: a stopped player still holds its decoders, which the film player needs.
        player?.let { p ->
            surface?.let { p.clearVideoSurfaceView(it) }
            p.removeListener(playerListener)
            p.release()
        }
        player = null
        builtHeaders = null
        error = null
    }

    private fun playerFor(headers: Map<String, String>): ExoPlayer {
        val existing = player
        if (existing != null && builtHeaders == headers) return existing
        existing?.let { it.removeListener(playerListener); it.release() }
        val p = PlayerFactory.buildLive(app, headers, Prefs(app).passthrough)
        // Captions start off; the viewer turns them on from the Captions button.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.addListener(playerListener)
        surface?.let { p.setVideoSurfaceView(it) }
        player = p
        builtHeaders = headers
        return p
    }

    private fun mediaItem(source: LiveSession.Source): MediaItem {
        val uri = Uri.parse(source.url)
        val path = uri.lastPathSegment?.lowercase() ?: ""
        val b = MediaItem.Builder().setUri(uri).setMediaId(source.key)
        when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }

    private val retry = Runnable {
        val p = player ?: return@Runnable
        p.prepare()
        p.playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (retries < RETRY_DELAYS_MS.size) {
                Log.i(TAG, "Live stream error ${e.errorCodeName}; retry ${retries + 1}")
                main.postDelayed(retry, RETRY_DELAYS_MS[retries++])
                return
            }
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The channel isn't available right now"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the channel's server"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This TV can't play this channel's picture"
                else -> "The channel couldn't be played"
            }
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retries = 0
        }

        override fun onPlaybackStateChanged(state: Int) {
            // A live channel doesn't end: the provider dropped the stream. Reconnect, with the same back-off as errors.
            if (state != Player.STATE_ENDED || session.playingKey == null) return
            val p = player ?: return
            if (retries < RETRY_DELAYS_MS.size) {
                p.seekToDefaultPosition()
                main.postDelayed(retry, RETRY_DELAYS_MS[retries++])
            } else {
                error = "The channel stopped"
                publish()
            }
        }
    }

    private fun publish() {
        val s = status()
        for (l in listeners.toList()) l.onLiveStatus(s)
    }

    fun status(): Status {
        val p = player
        val tracks = p?.currentTracks ?: Tracks.EMPTY
        return Status(
            key = session.playingKey,
            buffering = p?.playbackState == Player.STATE_BUFFERING,
            playing = p?.isPlaying == true,
            error = error,
            video = p?.videoFormat,
            audio = p?.audioFormat,
            videoSize = p?.videoSize ?: VideoSize.UNKNOWN,
            captions = tracksOf(tracks, C.TRACK_TYPE_TEXT),
            audioTracks = tracksOf(tracks, C.TRACK_TYPE_AUDIO),
        )
    }

    private fun tracksOf(tracks: Tracks, type: Int): List<Track> = buildList {
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() } } ?: "Track ${size + 1}"
                add(Track(gi, ti, label, g.isTrackSelected(ti)))
            }
        }
    }

    companion object {
        private const val TAG = "FirePlayerLive"
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)

        /** Every stream the app opens counts here, per provider account. */
        val budget = ConnectionBudget()

        @Volatile private var instance: PlayerHost? = null

        fun get(context: Context): PlayerHost =
            instance ?: synchronized(this) { instance ?: PlayerHost(context).also { instance = it } }

        /** The host if live playback was ever used in this process. */
        fun peek(): PlayerHost? = instance
    }
}
