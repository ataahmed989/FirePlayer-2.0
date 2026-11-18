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
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.fireplayer.data.Prefs
import app.fireplayer.player.Delays
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
        /** The viewer paused the channel. */
        val paused: Boolean = false,
        /** Playing (or paused) behind the live edge: Go live is offered until it's back at live. */
        val behindLive: Boolean = false,
    )

    data class Track(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

    interface Listener {
        fun onLiveStatus(status: Status)
        /** The captions to show now (empty when off or between lines). */
        fun onLiveCues(cues: List<Cue>) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var player: ExoPlayer? = null
    private var builtHeaders: Map<String, String>? = null
    private var surface: SurfaceView? = null
    private var retries = 0
    private var error: String? = null
    /** Captions the viewer turned on stay on from channel to channel (the kind chosen, CC1 or digital, first). */
    private var captionsOn = false
    private var captionsMime: String? = null
    private var cues: List<Cue> = emptyList()
    /** The captions on screen now (for a screen that starts showing them mid-line). */
    val currentCues: List<Cue> get() = cues
    /** Read by the live player's video renderer on the playback thread (see [PlayerFactory.buildLive]). */
    private val delays = Delays()
    /** The channel the audio delay was set on: another channel starts at 0 ms, as each film does. */
    private var delayKey: String? = null

    /**
     * Audio delay (full screen's Audio delay, microseconds; above zero the sound comes later), as the film player's.
     * Changes apply while playing; kept while the same channel plays, back to 0 ms on another channel.
     */
    var audioDelayUs: Long
        get() = delays.audioUs
        set(v) { delays.audioUs = v }

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
        l.onLiveCues(cues)
    }

    fun removeListener(l: Listener) { listeners -= l }

    /** Plays [source] (or keeps playing it when it already is). */
    fun tune(source: LiveSession.Source): LiveSession.Tune {
        var result = session.tune(source)
        if (result == LiveSession.Tune.AlreadyPlaying && streamDead()) {
            // The channel is still ours (its lease is held) but the stream gave up: try it again from the start.
            openStream(source)
            result = LiveSession.Tune.Started
        }
        publish()
        return result
    }

    private fun streamDead(): Boolean {
        val p = player ?: return true
        return error != null || p.playbackState == Player.STATE_IDLE
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

    /**
     * The screen showing live stopped. [owner] is that screen's surface: when another screen has attached its own
     * surface since (a new activity instance), this stale stop is ignored rather than stopping the new one's stream.
     */
    fun onScreenStopped(owner: Any? = null) {
        if (owner != null && surface != null && owner !== surface) return
        session.suspend()
        publish()
    }

    /** The viewer closed live playback, or a film is starting. */
    fun stop() {
        session.stop()
        publish()
    }

    fun setMuted(muted: Boolean) { player?.volume = if (muted) 0f else 1f }

    /** Paused by the viewer (not by the app going to the background, which closes the stream). */
    var paused = false
        private set

    /** Behind the live edge since a pause, until Go live or a reconnect brings it back. */
    private var behind = false

    /** Pauses or carries on. A paused channel falls behind live; it keeps its place while the buffer lasts. */
    fun togglePause() {
        val p = player ?: return
        if (session.playingKey == null) return
        paused = !paused
        if (paused) behind = true
        p.playWhenReady = !paused
        publish()
    }

    /**
     * Back to the live edge: a live HLS/DASH window seeks to its default position; a plain stream (MPEG-TS), which
     * can't seek, is opened again, which is where the provider is now.
     */
    fun goLive() {
        val p = player ?: return
        val source = session.source ?: return
        if (session.playingKey == null) return
        paused = false
        behind = false
        if (p.isCurrentMediaItemDynamic && p.isCurrentMediaItemSeekable) {
            p.seekToDefaultPosition()
            p.playWhenReady = true
        } else {
            openStream(source)
        }
        publish()
    }

    /** Whether playback is still behind live: a seekable live window says by its offset; a plain stream until reopened. */
    private fun stillBehind(p: ExoPlayer): Boolean {
        if (!behind) return false
        if (!paused && p.isCurrentMediaItemLive && p.isCurrentMediaItemDynamic) {
            val offset = p.currentLiveOffset
            if (offset != C.TIME_UNSET && offset <= LIVE_EDGE_SLACK_MS) behind = false
        }
        return behind
    }

    fun selectCaption(track: Track?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (track == null) {
            captionsOn = false
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            setCues(emptyList())
        } else {
            val group = p.currentTracks.groups.getOrNull(track.groupIndex) ?: return
            captionsOn = true
            captionsMime = group.getTrackFormat(track.trackIndex).sampleMimeType
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
        }
        p.trackSelectionParameters = b.build()
    }

    /** A new channel's tracks: captions that were on are turned on again there (the same kind when it has it). */
    private fun keepCaptions(p: ExoPlayer, tracks: Tracks) {
        if (!captionsOn) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isEmpty() || text.any { it.isSelected }) return
        val language = Prefs(app).subtitleLanguage
        val pick = text.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
            .let { all ->
                // The same kind as before; among those, the default subtitle language first.
                val kind = all.filter { (g, i) -> g.getTrackFormat(i).sampleMimeType == captionsMime }.ifEmpty { all }
                kind.firstOrNull { (g, i) -> language.isNotEmpty() && g.getTrackFormat(i).language?.startsWith(language) == true } ?: kind.firstOrNull()
            } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
            .build()
    }

    private fun setCues(list: List<Cue>) {
        if (list.isEmpty() && cues.isEmpty()) return
        cues = list
        for (l in listeners.toList()) l.onLiveCues(list)
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
        // A fresh stream starts at live.
        paused = false
        behind = false
        if (source.key != delayKey) {
            delayKey = source.key
            delays.audioUs = 0L
        }
        val p = playerFor(source.headers)
        // The shared Defaults (Settings › Defaults, the same values films use): the sound in the chosen language when
        // the channel has it, and that language's captions first once captions are on.
        val prefs = Prefs(app)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage.ifEmpty { null })
            .setPreferredTextLanguage(prefs.subtitleLanguage.ifEmpty { null })
            .build()
        setCues(emptyList())
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
        paused = false
        behind = false
        setCues(emptyList())
    }

    private fun playerFor(headers: Map<String, String>): ExoPlayer {
        val existing = player
        if (existing != null && builtHeaders == headers) return existing
        existing?.let { it.removeListener(playerListener); it.release() }
        val p = PlayerFactory.buildLive(app, headers, Prefs(app).passthrough, delays)
        // Captions start off; the viewer turns them on from the Captions button (and they then stay on).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn).build()
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

    /** Reconnects after an error or a dropped stream: back to the live edge, prepared again. */
    private val retry = Runnable {
        val p = player ?: return@Runnable
        // Reconnecting lands at the live edge: whatever was behind has recalibrated itself.
        behind = false
        paused = false
        p.seekToDefaultPosition()
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
        p.playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onTracksChanged(tracks: Tracks) {
            player?.let { keepCaptions(it, tracks) }
        }

        override fun onCues(cueGroup: CueGroup) = setCues(if (captionsOn) cueGroup.cues else emptyList())

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
            if (player == null) return
            if (retries < RETRY_DELAYS_MS.size) {
                main.removeCallbacks(retry)
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
            paused = paused && session.playingKey != null,
            behindLive = p != null && session.playingKey != null && stillBehind(p),
        )
    }

    private fun tracksOf(tracks: Tracks, type: Int): List<Track> = buildList {
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = f.label ?: captionName(f) ?: f.language?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() } } ?: "Track ${size + 1}"
                add(Track(gi, ti, label, g.isTrackSelected(ti)))
            }
        }
    }

    /** TV closed captions found in the picture (the extractor drops the labels they were declared with). */
    private fun captionName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CC" + (if (f.accessibilityChannel in 1..4) f.accessibilityChannel else 1)
        MimeTypes.APPLICATION_CEA708 -> "Digital captions"
        else -> null
    }

    companion object {
        private const val TAG = "FirePlayerLive"
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)
        /** Within this of the live edge counts as live again (the player itself sits a few seconds back). */
        private const val LIVE_EDGE_SLACK_MS = 20_000L

        /** Every stream the app opens counts here, per provider account. */
        val budget = ConnectionBudget()

        @Volatile private var instance: PlayerHost? = null

        fun get(context: Context): PlayerHost =
            instance ?: synchronized(this) { instance ?: PlayerHost(context).also { instance = it } }

        /** The host if live playback was ever used in this process. */
        fun peek(): PlayerHost? = instance
    }
}
