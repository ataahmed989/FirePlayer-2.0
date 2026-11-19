package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.live.Channel
import app.fireplayer.live.LiveDb
import app.fireplayer.live.Programme
import app.fireplayer.live.ui.LogoCache
import app.fireplayer.live.ui.LogoView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import java.util.Date
import java.util.concurrent.Executors

/**
 * Home's top-right window (330 x 195 dp), reserved for the live picture that will follow the viewer across tabs. Until
 * that exists it shows the channels last watched on Live TV, read-only: each one's logo on its plate, its name, and
 * what's on now. Read from the Live TV database on a background thread when the page starts; nothing here takes focus.
 */
@SuppressLint("ViewConstructor")
class RecentWindow(context: Context, private val t: Tokens) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val logos = LogoCache(t.dp(VodSizes.RECENT_LOGO_PX)) { _, _ -> refreshLogos() }
    private val lines = ArrayList<Line>()
    private val emptyText = t.label(context, VodSizes.RECENT_EMPTY).apply {
        text = "Channels you watch on Live TV appear here"
        maxLines = 3
    }
    private var shown: List<Channel> = emptyList()
    private var generation = 0
    @Volatile private var released = false

    private class Line(val row: LinearLayout, val logo: LogoView, val name: TextView, val now: TextView) {
        var logoUrl: String? = null
    }

    init {
        orientation = VERTICAL
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val pad = t.dp(VodSizes.RECENT_PAD_X)
        setPadding(pad, t.dp(VodSizes.RECENT_PAD_TOP), pad, t.dp(VodSizes.RECENT_PAD_BOTTOM))
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_history)
            setColorFilter(t.headingIcon)
        }, LayoutParams(t.dp(VodSizes.RECENT_ICON), t.dp(VodSizes.RECENT_ICON)))
        head.addView(t.label(context, VodSizes.RECENT_HEAD).apply { text = "Recent channels"; setTextColor(t.heading) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(VodSizes.RECENT_ICON_GAP) })
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(VodSizes.RECENT_HEAD_H)))
        for (i in 0 until ROWS) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = GONE
            }
            val logo = LogoView(context)
            row.addView(logo, LayoutParams(t.dp(VodSizes.RECENT_LOGO_W), t.dp(VodSizes.RECENT_LOGO_H)))
            val text = LinearLayout(context).apply { orientation = VERTICAL }
            val name = t.label(context, VodSizes.RECENT_NAME)
            val now = t.label(context, VodSizes.RECENT_NOW)
            text.addView(name, LayoutParams(t.dp(VodSizes.RECENT_TEXT_W), LayoutParams.WRAP_CONTENT))
            text.addView(now, LayoutParams(t.dp(VodSizes.RECENT_TEXT_W), LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.RECENT_LINE_GAP) })
            row.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(VodSizes.RECENT_LOGO_GAP) })
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(VodSizes.RECENT_ROW_H)).apply {
                topMargin = t.dp(if (i == 0) VodSizes.RECENT_FIRST_TOP else VodSizes.RECENT_ROW_GAP)
            })
            lines += Line(row, logo, name, now)
        }
        addView(emptyText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.RECENT_EMPTY_TOP) })
        applyLook()
    }

    fun applyLook() {
        Glass.background(this, Glass.Kind.PANEL)
    }

    /** Reads the channels last watched and what's on them now (background thread), then shows them. */
    fun refresh() {
        if (released) return
        val gen = ++generation
        val ctx = context.applicationContext
        pool.execute {
            val result = try {
                val db = LiveDb.get(ctx)
                val channels = db.recent().filter { !it.blocked }.take(ROWS)
                val now = System.currentTimeMillis()
                val progs = db.programmes(channels, now, now + 1)
                channels to progs
            } catch (e: Exception) {
                null
            }
            main.post {
                if (released || gen != generation || result == null) return@post
                show(result.first, result.second)
            }
        }
    }

    fun release() {
        released = true
        logos.onChange = null
        logos.clear()
    }

    private fun show(channels: List<Channel>, programmes: Map<app.fireplayer.live.ChannelKey, List<Programme>>) {
        shown = channels
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeFormat(context)
        lines.forEachIndexed { i, line ->
            val ch = channels.getOrNull(i)
            if (ch == null) {
                line.row.visibility = GONE
                return@forEachIndexed
            }
            line.row.visibility = VISIBLE
            line.name.text = ch.name
            val on = programmes[ch.key]?.firstOrNull { it.isOn(now) }
            line.now.text = on?.let { "${it.title} · ${time.format(Date(it.start))}–${time.format(Date(it.stop))}" } ?: "No guide for now"
            line.logoUrl = ch.logo
        }
        emptyText.visibility = if (channels.isEmpty()) VISIBLE else GONE
        refreshLogos()
    }

    private fun refreshLogos() {
        for (line in lines) {
            val url = line.logoUrl
            if (url.isNullOrEmpty()) {
                line.logo.set(null, plate = false)
                continue
            }
            val logo = logos.get(url)
            line.logo.set(logo, plate = logo != null || !logos.missing(url))
        }
    }

    private companion object {
        const val ROWS = 3
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerRecent").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
}
