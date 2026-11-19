package app.fireplayer.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import app.fireplayer.shell.Tokens

/**
 * The buffering symbol: a blue arc turning on a faint ring. It's drawn once; turning only changes the view's
 * rotation, so nothing is laid out or drawn again frame by frame. Its look is in [Tokens] (SPINNER_*).
 */
class BufferingSpinner(context: Context, accent: Int) : View(context) {
    private val stroke = Tokens.SPINNER_STROKE_DP * resources.displayMetrics.density
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke * 1.6f; color = Tokens.SPINNER_SHADE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = Tokens.SPINNER_RING
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = accent
    }
    private val box = RectF()
    private var turn: ObjectAnimator? = null

    /** Turning (only while it shows: a hidden one costs nothing). */
    var spinning = false
        set(v) {
            if (field == v) return
            field = v
            if (v && isAttachedToWindow) start() else stop()
        }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun start() {
        val a = turn ?: ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
            duration = Tokens.SPINNER_TURN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }.also { turn = it }
        if (!a.isStarted) a.start()
    }

    private fun stop() {
        turn?.cancel()
        rotation = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (spinning) start()
    }

    override fun onDetachedFromWindow() {
        turn?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = stroke
        box.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(box, shade)
        canvas.drawOval(box, ring)
        canvas.drawArc(box, -90f, Tokens.SPINNER_SWEEP, false, arc)
    }
}

/**
 * The buffering symbol over a picture, with a line or two of text under it when there's something to say
 * ("Reconnecting…"). One for a screen: it's put over the picture's middle wherever the picture is ([place]), smaller
 * over a miniature, by translation and scale only, so it can follow a picture that moves. When it shows is decided by
 * [BusySigns]. Never takes focus.
 */
class BusyOverlay(context: Context, t: Tokens) : FrameLayout(context) {
    private val boxW = t.dp(BOX_W_DP)
    private val boxH = t.dp(BOX_H_DP)
    private val spinnerHalf = t.dpf(Tokens.SPINNER_DP) / 2f
    private val spinner = BufferingSpinner(context, t.accent)
    private val label = t.label(context, 13f, t.semibold).apply {
        maxLines = 2
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setLineSpacing(t.dpf(2f), 1f)
        setShadowLayer(t.dpf(4f), 0f, 0f, 0xCC000000.toInt())
        visibility = INVISIBLE
    }
    private var placed = false
    private var busy = false

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        visibility = GONE
        val size = t.dp(Tokens.SPINNER_DP)
        addView(spinner, LayoutParams(size, size, Gravity.CENTER))
        // A fixed box: new text never lays anything out again.
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(TEXT_H_DP), Gravity.TOP).apply {
            topMargin = boxH / 2 + size / 2 + t.dp(TEXT_GAP_DP)
        })
    }

    /** Its layout params in a FrameLayout: a fixed box at the top left, moved over the picture by [place]. */
    fun params(): LayoutParams = LayoutParams(boxW, boxH, Gravity.TOP or Gravity.START)

    /**
     * Over the picture whose middle is at ([cx], [cy]) in the parent; [fullness] is how much of full screen the
     * picture is (0 a miniature, 1 full screen, between while it moves).
     */
    fun place(cx: Float, cy: Float, fullness: Float) {
        translationX = cx - boxW / 2f
        translationY = cy - boxH / 2f
        val s = Tokens.SPINNER_MINI_SCALE + (1f - Tokens.SPINNER_MINI_SCALE) * fullness.coerceIn(0f, 1f)
        spinner.scaleX = s
        spinner.scaleY = s
        label.translationY = -(1f - s) * spinnerHalf
        if (!placed) { placed = true; apply() }
    }

    /** There's no picture to be over. */
    fun away() {
        if (!placed) return
        placed = false
        apply()
    }

    /** The symbol turning ([spinning]), with [text] under it (null: none). */
    fun show(spinning: Boolean, text: String?) {
        val line = if (spinning) text else null
        if (!TextUtils.equals(label.text, line ?: "")) label.text = line ?: ""
        label.visibility = if (line == null) INVISIBLE else VISIBLE
        if (busy != spinning) { busy = spinning; apply() }
    }

    private fun apply() {
        val on = placed && busy
        visibility = if (on) VISIBLE else GONE
        spinner.spinning = on
    }

    private companion object {
        const val BOX_W_DP = 340
        const val BOX_H_DP = 148
        const val TEXT_H_DP = 40
        const val TEXT_GAP_DP = 8
    }
}
