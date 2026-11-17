package app.fireplayer.live.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.live.Channel
import app.fireplayer.live.Programme
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import java.util.Date

/**
 * The full-screen live controls (design-layout, "Full-screen primary controls"): channel, programme, schedule, next
 * and a clock at the top; the original picture/sound/source pods, a thin non-selectable programme tracker, and the
 * primary buttons at the bottom — TV Guide and Channel History on the left, Captions and Favorite on the right, the
 * more-options chevron centred below. The same layout in both looks; glass only changes the button surfaces.
 */
class LiveControls(context: Context, private val t: Tokens) : FrameLayout(context) {

    interface Actions {
        fun onGuide()
        fun onHistory()
        fun onCaptions()
        fun onFavorite()
        fun onMore()
    }

    var actions: Actions? = null

    private val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val channelName = t.label(context, 20f, t.semibold)
    private val title = t.label(context, 28f, t.bold)
    private val schedule = t.label(context, 16f)
    private val next = t.label(context, 16f, color = t.textMuted)
    private val clock = t.label(context, 17f)
    private val pods = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val startTime = t.label(context, 15f)
    private val endTime = t.label(context, 15f)
    private val tracker = Tracker(context)
    private val noGuide = t.label(context, 15f, color = t.textMuted).apply { text = "No programme information" }
    private val top = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topScrim = View(context).apply { setBackgroundResource(R.drawable.scrim_top) }
    private val bottomScrim = View(context).apply { setBackgroundResource(R.drawable.scrim_bottom) }

    val guideButton: View
    private val historyButton: View
    private val captionsButton: View
    private val captionsText: TextView
    private val favoriteButton: View
    private val favoriteIcon: ImageView
    private val moreButton: ImageView
    private val buttons = ArrayList<View>()

    /** Info only (after a channel change) versus the full controls. */
    var controlsShown = false
        private set

    init {
        val pad = t.dp(28)
        addView(topScrim, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(190), Gravity.TOP))
        addView(bottomScrim, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(230), Gravity.BOTTOM))

        // Top left: logo | name, programme, schedule, next.
        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(t.dp(44), t.dp(30)))
            addView(View(context).apply { setBackgroundColor(0x80FFFFFF.toInt()) }, LinearLayout.LayoutParams(t.dp(1), t.dp(22)).apply { leftMargin = t.dp(12); rightMargin = t.dp(12) })
            addView(channelName)
        }
        top.addView(identity)
        top.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(10) })
        top.addView(schedule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(8) })
        top.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(6) })
        addView(top, LayoutParams(t.dp(600), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = pad; topMargin = t.dp(22) })
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { rightMargin = pad; topMargin = t.dp(26) })

        // Bottom: pods, tracker, buttons, chevron.
        bottom.addView(pods, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val trackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startTime)
            addView(tracker, LinearLayout.LayoutParams(0, t.dp(14), 1f).apply { leftMargin = t.dp(14); rightMargin = t.dp(14) })
            addView(endTime)
            addView(noGuide)
        }
        bottom.addView(trackRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(12) })

        val row = FrameLayout(context)
        val left = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val right = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val guide = circleButton(R.drawable.ic_guide, "TV Guide") { actions?.onGuide() }
        guideButton = guide.second
        left.addView(guide.first)
        val history = circleButton(R.drawable.ic_history, "Channel History") { actions?.onHistory() }
        historyButton = history.second
        left.addView(history.first, spaced())
        val cc = pillButton("Captions") { actions?.onCaptions() }
        captionsButton = cc.first
        captionsText = cc.second
        right.addView(captionsButton)
        val fav = circleButton(R.drawable.ic_star, "Favorite") { actions?.onFavorite() }
        favoriteButton = fav.second
        favoriteIcon = fav.second.getChildAt(0) as ImageView
        right.addView(fav.first, spaced())
        row.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START))
        row.addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END))
        bottom.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(16) })

        moreButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = iconColors()
            isFocusable = true
            contentDescription = "More options"
            setPadding(t.dp(8), t.dp(4), t.dp(8), t.dp(4))
            background = focusPill()
            setOnClickListener { actions?.onMore() }
        }
        buttons += moreButton
        bottom.addView(moreButton, LinearLayout.LayoutParams(t.dp(44), t.dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = t.dp(2) })
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = pad; rightMargin = pad; bottomMargin = t.dp(10) })

        // Left stops at TV Guide rather than wandering off the controls.
        guideButton.nextFocusLeftId = guideButton.id
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        visibility = GONE
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(26) }

    private fun iconColors() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(t.accent, t.text))

    private fun focusPill() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { setColor(t.focusFill); cornerRadius = t.dpf(14f) })
    }

    /** A circle button with its label underneath: returns the column (to lay out) and the circle (to focus). */
    private fun circleButton(icon: Int, label: String, onClick: () -> Unit): Pair<View, FrameLayout> {
        val circle = FrameLayout(context).apply {
            isFocusable = true
            id = generateViewId()
            contentDescription = label
            Glass.background(this, Glass.Kind.CIRCLE)
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = iconColors()
                isDuplicateParentStateEnabled = true
            }, LayoutParams(t.dp(24), t.dp(24), Gravity.CENTER))
        }
        Tokens.growOnFocus(circle, Tokens.GROW_CIRCLE)
        buttons += circle
        val text = t.label(context, 14f).apply { this.text = label; gravity = Gravity.CENTER }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(circle, LinearLayout.LayoutParams(t.dp(50), t.dp(50)))
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(6) })
        }
        return column to circle
    }

    private fun pillButton(label: String, onClick: () -> Unit): Pair<View, TextView> {
        val text = t.label(context, 15f).apply {
            this.text = "Off"
            setTextColor(iconColors())
            isDuplicateParentStateEnabled = true
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = label
            setPadding(t.dp(16), 0, t.dp(18), 0)
            Glass.background(this, Glass.Kind.PILL)
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_cc)
                imageTintList = iconColors()
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(t.dp(24), t.dp(24)).apply { rightMargin = t.dp(8) })
            addView(text)
        }
        Tokens.growOnFocus(pill, Tokens.GROW_PILL)
        buttons += pill
        val caption = t.label(context, 14f).apply { this.text = label; gravity = Gravity.CENTER }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, t.dp(50)))
            addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(6) })
        }
        return column to text
    }

    /** Re-applies the look to the button surfaces (Settings › Look changed). */
    fun applyLook() {
        for (b in buttons) {
            if (b === moreButton) continue
            Glass.background(b, if (b is LinearLayout) Glass.Kind.PILL else Glass.Kind.CIRCLE)
        }
        for (i in 0 until pods.childCount) Glass.background(pods.getChildAt(i), Glass.Kind.CHIP)
    }

    // ---- Content --------------------------------------------------------------------------------------------------

    fun setChannel(channel: Channel, now: Programme?, nextUp: Programme?, playlistName: String, logoBitmap: Bitmap?, clockNow: Long) {
        val tf = DateFormat.getTimeFormat(context)
        channelName.text = channel.name
        if (logoBitmap != null) { logo.setImageBitmap(logoBitmap); logo.visibility = VISIBLE } else logo.visibility = GONE
        (channelName.parent as View).let { (it as ViewGroup).getChildAt(1).visibility = logo.visibility }
        title.text = now?.title?.takeIf { it.isNotBlank() } ?: channel.name
        schedule.text = if (now != null) "${tf.format(Date(now.start))} – ${tf.format(Date(now.stop))}" else ""
        schedule.visibility = if (now != null) VISIBLE else GONE
        next.text = nextUp?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
        next.visibility = if (nextUp != null) VISIBLE else GONE
        favoriteIcon.setImageResource(if (channel.favorite) R.drawable.ic_star_filled else R.drawable.ic_star)
        favoriteButton.contentDescription = if (channel.favorite) "Remove from Favorites" else "Add to Favorites"
        this.playlistName = playlistName
        setProgramme(now, clockNow)
        tick(clockNow)
    }

    private var playlistName = ""
    private var programme: Programme? = null

    private fun setProgramme(p: Programme?, now: Long) {
        programme = p
        val tf = DateFormat.getTimeFormat(context)
        val has = p != null
        startTime.visibility = if (has) VISIBLE else GONE
        endTime.visibility = if (has) VISIBLE else GONE
        tracker.visibility = if (has) VISIBLE else GONE
        noGuide.visibility = if (has) GONE else VISIBLE
        if (p != null) {
            startTime.text = tf.format(Date(p.start))
            updateEnd(p, now)
        }
    }

    private fun updateEnd(p: Programme, now: Long) {
        val tf = DateFormat.getTimeFormat(context)
        val left = ((p.stop - now) / 60_000).coerceAtLeast(0)
        endTime.text = "${tf.format(Date(p.stop))}  |  $left min left"
        tracker.fraction = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
    }

    /** Clock and tracker, every few seconds while shown. */
    fun tick(now: Long) {
        clock.text = DateFormat.format("EEE, MMM d", now).toString() + " · " + DateFormat.getTimeFormat(context).format(Date(now))
        programme?.let { updateEnd(it, now) }
    }

    fun setStatus(s: PlayerHost.Status) {
        val labels = ArrayList<String>()
        s.video?.let { v ->
            val w = if (s.videoSize.width > 0) s.videoSize.width else v.width
            val h = if (s.videoSize.height > 0) s.videoSize.height else v.height
            if (w > 0 && h > 0) labels += "${w}×$h"
            if (v.frameRate > 0) labels += String.format(java.util.Locale.US, if (v.frameRate % 1f == 0f) "%.0f FPS" else "%.2f FPS", v.frameRate)
            val codec = when {
                v.sampleMimeType?.contains("hevc") == true -> "HEVC"
                v.sampleMimeType?.contains("avc") == true -> "H.264"
                v.sampleMimeType?.contains("av01") == true -> "AV1"
                v.sampleMimeType?.contains("mpeg2") == true -> "MPEG-2"
                else -> v.codecs?.substringBefore('.')?.uppercase()
            }
            val hdr = when (v.colorInfo?.colorTransfer) {
                androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
            if (codec != null) labels += "$codec · $hdr"
        }
        s.audio?.let { a ->
            labels += when (a.channelCount) {
                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (a.channelCount > 0) "${a.channelCount} ch" else "Sound"
            }
        }
        if (playlistName.isNotBlank()) labels += playlistName
        pods.removeAllViews()
        for ((i, l) in labels.withIndex()) {
            val chip = t.label(context, 15f).apply {
                text = l
                setPadding(t.dp(14), t.dp(6), t.dp(14), t.dp(6))
                Glass.background(this, Glass.Kind.CHIP)
            }
            pods.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = t.dp(10) })
        }
        val cc = s.captions.firstOrNull { it.selected }
        captionsText.text = cc?.label ?: "Off"
    }

    // ---- Visibility -----------------------------------------------------------------------------------------------

    /** The full controls with focus on [focus] (TV Guide by default). */
    fun showControls(focus: View? = null) {
        visibility = VISIBLE
        controlsShown = true
        bottom.visibility = VISIBLE
        top.visibility = VISIBLE
        clock.visibility = VISIBLE
        bottomScrim.visibility = VISIBLE
        topScrim.visibility = VISIBLE
        alpha = 1f
        (focus ?: guideButton).requestFocus()
    }

    /** Only the channel and programme at the top (after a channel change): nothing takes focus. */
    fun showInfo() {
        visibility = VISIBLE
        controlsShown = false
        top.visibility = VISIBLE
        clock.visibility = VISIBLE
        topScrim.visibility = VISIBLE
        bottom.visibility = GONE
        bottomScrim.visibility = GONE
    }

    fun hideAll() {
        visibility = GONE
        controlsShown = false
    }

    /** The thin blue programme progress line: drawn, never focused or seekable. */
    private inner class Tracker(context: Context) : View(context) {
        var fraction = 0f
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        init {
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val h = t.dpf(4f)
            val cy = height / 2f
            r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
            p.color = 0x4DFFFFFF
            canvas.drawRoundRect(r, h, h, p)
            r.right = width * fraction
            p.color = t.accent
            canvas.drawRoundRect(r, h, h, p)
        }
    }
}
