package app.fireplayer.live.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import app.fireplayer.R
import app.fireplayer.data.Prefs
import app.fireplayer.live.Channel
import app.fireplayer.live.Programme
import app.fireplayer.player.FpsMeter
import app.fireplayer.player.MediaSpec
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The full-screen live controls (res/layout/live_controls.xml), built to the VOD player's measurements so both
 * players read as one: the same top block, pods (and the same Settings › Look › Info pods choices), time line, round
 * buttons and pills, and names that appear under a button only while it's selected.
 *
 * Left: TV Guide and Channel History. While the channel is paused or behind live, Play/Pause and Go live come first
 * and push them along. Right: Captions and Favorite. Down opens more options (the chevron), as in the VOD player.
 */
class LiveControls(context: Context, private val t: Tokens) : FrameLayout(context) {

    interface Actions {
        fun onGuide()
        fun onHistory()
        fun onCaptions()
        fun onFavorite()
        fun onPlayPause()
        fun onGoLive()
    }

    var actions: Actions? = null

    private val prefs = Prefs(context)

    // Inflated first: the view properties below look into it as they're initialised.
    init {
        LayoutInflater.from(context).inflate(R.layout.live_controls, this, true)
    }
    private val top: View = findViewById(R.id.live_top)
    private val bottom: View = findViewById(R.id.live_bottom)
    private val scrimTop: View = findViewById(R.id.live_scrim_top)
    private val scrimBottom: View = findViewById(R.id.live_scrim_bottom)
    private val logo: ImageView = findViewById(R.id.live_logo)
    private val logoDivider: View = findViewById(R.id.live_logo_divider)
    private val channelName: TextView = findViewById(R.id.live_channel)
    private val title: TextView = findViewById(R.id.live_title)
    private val schedule: TextView = findViewById(R.id.live_schedule)
    private val next: TextView = findViewById(R.id.live_next)
    private val clock: TextView = findViewById(R.id.live_clock)
    private val chipsRow: ViewGroup = findViewById(R.id.live_chips)
    private val chipResolution: TextView = findViewById(R.id.live_chip_resolution)
    private val chipFps: TextView = findViewById(R.id.live_chip_fps)
    private val chipFormat: TextView = findViewById(R.id.live_chip_format)
    private val chipAudio: TextView = findViewById(R.id.live_chip_audio)
    private val chipSource: TextView = findViewById(R.id.live_chip_source)
    private val startTime: TextView = findViewById(R.id.live_start)
    private val endTime: TextView = findViewById(R.id.live_end)
    private val noGuide: TextView = findViewById(R.id.live_no_guide)
    private val line: ProgrammeLine = findViewById(R.id.live_line)

    private val btnPlay: ImageButton = findViewById(R.id.live_btn_play)
    private val goLiveGroup: View = findViewById(R.id.live_go_live_group)
    private val btnGoLive: ImageButton = findViewById(R.id.live_btn_go_live)
    val guideButton: ImageButton = findViewById(R.id.live_btn_guide)
    private val btnHistory: ImageButton = findViewById(R.id.live_btn_history)
    private val btnCaptions: View = findViewById(R.id.live_btn_cc)
    private val captionsValue: TextView = findViewById(R.id.live_cc_value)
    private val btnFavorite: ImageButton = findViewById(R.id.live_btn_favorite)
    private val favoriteLabel: TextView = findViewById(R.id.live_favorite_label)

    /** Info only (after a channel change) versus the full controls. */
    var controlsShown = false
        private set

    /** Play/Pause and Go live are showing (the channel is paused or behind live). */
    var catchUpShown = false
        private set

    private var playlistName = ""
    private var programme: Programme? = null

    init {
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        visibility = GONE
        // As the VOD player: circles grow 1.08, pills 1.04, and the name fades in under the selected one.
        focusEffect(btnPlay, Tokens.GROW_CIRCLE, null)
        focusEffect(btnGoLive, Tokens.GROW_CIRCLE, findViewById(R.id.live_go_live_label))
        focusEffect(guideButton, Tokens.GROW_CIRCLE, findViewById(R.id.live_guide_label))
        focusEffect(btnHistory, Tokens.GROW_CIRCLE, findViewById(R.id.live_history_label))
        focusEffect(btnCaptions, Tokens.GROW_PILL, findViewById(R.id.live_cc_label))
        focusEffect(btnFavorite, Tokens.GROW_CIRCLE, favoriteLabel)
        btnPlay.setOnClickListener { actions?.onPlayPause() }
        btnGoLive.setOnClickListener { actions?.onGoLive() }
        guideButton.setOnClickListener { actions?.onGuide() }
        btnHistory.setOnClickListener { actions?.onHistory() }
        btnCaptions.setOnClickListener { actions?.onCaptions() }
        btnFavorite.setOnClickListener { actions?.onFavorite() }
        // Left stops at the first button rather than wandering off the controls; right at the last.
        listOf(btnPlay, btnGoLive, guideButton, btnHistory, btnCaptions, btnFavorite).forEach { if (it.id == View.NO_ID) it.id = generateViewId() }
        applyLook()
    }

    private fun focusEffect(button: View, scale: Float, label: View?) {
        button.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(Tokens.FADE_MS).setInterpolator(EASE).start()
            if (label != null) {
                label.pivotX = v.left + v.width / 2f - label.left
                label.pivotY = v.top + v.height / 2f - label.top
                if (hasFocus) label.visibility = VISIBLE
                label.animate().alpha(if (hasFocus) 1f else 0f).scaleX(s).scaleY(s).setDuration(Tokens.FADE_MS).setInterpolator(EASE)
                    .withEndAction { if (!v.isFocused) label.visibility = INVISIBLE }
                    .start()
            }
        }
    }

    /** The look's surfaces, exactly as the VOD player gives its own (Glass.background keeps sizes). */
    fun applyLook() {
        for (b in listOf(btnPlay, btnGoLive, guideButton, btnHistory, btnFavorite)) Glass.background(b, Glass.Kind.CIRCLE)
        Glass.background(btnCaptions, Glass.Kind.PILL)
        for (chip in listOf(chipResolution, chipFps, chipFormat, chipAudio, chipSource)) Glass.background(chip, Glass.Kind.CHIP)
        chipsRow.clipChildren = !Glass.on
        (chipsRow as? ViewGroup)?.clipToPadding = !Glass.on
    }

    // ---- Content --------------------------------------------------------------------------------------------------

    fun setChannel(channel: Channel, now: Programme?, nextUp: Programme?, playlistName: String, logoBitmap: Bitmap?, clockNow: Long) {
        val tf = DateFormat.getTimeFormat(context)
        channelName.text = channel.name
        if (logoBitmap != null) logo.setImageBitmap(logoBitmap)
        logo.visibility = if (logoBitmap != null) VISIBLE else GONE
        logoDivider.visibility = logo.visibility
        title.text = now?.title?.takeIf { it.isNotBlank() } ?: channel.name
        schedule.text = if (now != null) "${tf.format(Date(now.start))} – ${tf.format(Date(now.stop))}" else ""
        schedule.visibility = if (now != null) VISIBLE else GONE
        next.text = nextUp?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
        next.visibility = if (nextUp != null) VISIBLE else GONE
        btnFavorite.setImageResource(if (channel.favorite) R.drawable.ic_star_filled else R.drawable.ic_star)
        val favText = if (channel.favorite) "Remove from Favorites" else "Add to Favorites"
        btnFavorite.contentDescription = favText
        this.playlistName = playlistName
        setProgramme(now, clockNow)
        tick(clockNow)
    }

    private fun setProgramme(p: Programme?, now: Long) {
        programme = p
        val has = p != null
        startTime.visibility = if (has) VISIBLE else GONE
        endTime.visibility = if (has) VISIBLE else GONE
        line.visibility = if (has) VISIBLE else GONE
        noGuide.visibility = if (has) GONE else VISIBLE
        if (p != null) {
            startTime.text = DateFormat.getTimeFormat(context).format(Date(p.start))
            updateEnd(p, now)
        }
    }

    private fun updateEnd(p: Programme, now: Long) {
        val left = ((p.stop - now) / 60_000).coerceAtLeast(0)
        endTime.text = "${DateFormat.getTimeFormat(context).format(Date(p.stop))} · $left min left"
        line.fraction = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
    }

    /** Clock and tracker, every few seconds while shown. */
    fun tick(now: Long) {
        clock.text = DateFormat.format("EEE, MMM d", now).toString() + " · " + DateFormat.getTimeFormat(context).format(Date(now))
        programme?.let { updateEnd(it, now) }
    }

    /** Pods as the VOD player writes them, each hidden when turned off in Settings › Look › Info pods. */
    @OptIn(UnstableApi::class)
    fun setStatus(s: PlayerHost.Status) {
        val hidden = prefs.hiddenChips
        fun pod(chip: TextView, key: String, text: String?) {
            val shown = text?.takeIf { key !in hidden }
            chip.text = shown
            chip.visibility = if (shown == null) GONE else VISIBLE
        }
        val v = s.video
        val w = s.videoSize.width.takeIf { it > 0 } ?: v?.width ?: 0
        val h = s.videoSize.height.takeIf { it > 0 } ?: v?.height ?: 0
        pod(chipResolution, "quality", if (w > 0 && h > 0) "$w×$h" else null)
        val fps = v?.frameRate ?: Format.NO_VALUE.toFloat()
        pod(chipFps, "fps", if (fps > 0) fpsLabel(fps) else null)
        val spec = if (v != null || s.audio != null) MediaSpec.of(v, s.audio) else null
        pod(chipFormat, "format", spec?.pictureLine())
        pod(chipAudio, "sound", spec?.soundLine() ?: MediaSpec.channelsLabel(s.audio?.channelCount ?: Format.NO_VALUE))
        pod(chipSource, "source", playlistName.takeIf { it.isNotBlank() })
        val cc = s.captions.firstOrNull { it.selected }
        captionsValue.text = cc?.label ?: "Off"
        setCatchUp(s.paused, s.behindLive)
    }

    private fun fpsLabel(fps: Float): String {
        val value = FpsMeter.snap(fps)
        val text = if (abs(value - value.roundToInt()) < 0.001f) value.roundToInt().toString()
            else "%.3f".format(Locale.US, value).trimEnd('0')
        return "$text FPS"
    }

    /**
     * Play/Pause and Go live while the channel is paused or behind live (they take the start of the row and move
     * TV Guide and Channel History along); gone once it's back at live.
     */
    private fun setCatchUp(paused: Boolean, behind: Boolean) {
        btnPlay.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        btnPlay.contentDescription = if (paused) "Play" else "Pause"
        val show = paused || behind
        if (show == catchUpShown) return
        catchUpShown = show
        val hadFocus = btnPlay.hasFocus() || btnGoLive.hasFocus()
        btnPlay.visibility = if (show) VISIBLE else GONE
        goLiveGroup.visibility = if (show) VISIBLE else GONE
        // Focus that was on a button that just went moves to TV Guide.
        if (!show && hadFocus && controlsShown) guideButton.requestFocus()
    }

    // ---- Visibility -----------------------------------------------------------------------------------------------

    /** The full controls; focus on Play/Pause when it shows (paused), else on TV Guide, unless [focus] is given. */
    fun showControls(focus: View? = null) {
        visibility = VISIBLE
        controlsShown = true
        top.visibility = VISIBLE
        bottom.visibility = VISIBLE
        scrimTop.visibility = VISIBLE
        scrimBottom.visibility = VISIBLE
        alpha = 1f
        (focus ?: if (catchUpShown) btnPlay else guideButton).requestFocus()
    }

    fun focusPlay() {
        if (catchUpShown) btnPlay.requestFocus()
    }

    /** Only the channel and programme at the top (after a channel change): nothing takes focus. */
    fun showInfo() {
        visibility = VISIBLE
        controlsShown = false
        top.visibility = VISIBLE
        scrimTop.visibility = VISIBLE
        bottom.visibility = GONE
        scrimBottom.visibility = GONE
    }

    fun hideAll() {
        visibility = GONE
        controlsShown = false
    }

    private companion object {
        val EASE = DecelerateInterpolator(1.4f)
    }
}

/** The thin blue programme progress line (VOD time line's 4dp track): drawn only, never focused or seekable. */
class ProgrammeLine @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val accent = ContextCompat.getColor(context, R.color.accent)

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val h = 4f * density
        val cy = height / 2f
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        p.color = 0x4DFFFFFF
        canvas.drawRoundRect(r, h, h, p)
        r.right = width * fraction
        p.color = accent
        canvas.drawRoundRect(r, h, h, p)
    }
}
