package app.fireplayer.vod.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget
import app.fireplayer.vod.Card
import app.fireplayer.vod.data.VodArt
import kotlin.math.ceil

/**
 * One card in a row or the See all grid, drawn in [onDraw] (no child views, so a new title or picture only redraws
 * it and nothing is laid out). A poster or a wide Continue Watching card (name, caption line and progress bar inside
 * its bottom), or the row's "See all" card. Until its picture comes it shows the name on a dark gradient. The white
 * ring round the chosen card is drawn just outside it, as strong as [focus]; it stays 3 dp however much the card is
 * grown. Its picture is decoded at the grown size, so it's sharp at both sizes.
 */
@SuppressLint("ViewConstructor")
class CardView(context: Context, private val t: Tokens, val kind: Kind) : View(context), ImageTarget {

    enum class Kind { POSTER, WIDE, SEE_ALL }

    var card: Card? = null
        private set

    /** How chosen it is: the ring's strength (and the See all card's white), 0..1. */
    var focus = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (field == c) return
            field = c
            invalidate()
        }

    override var imageKey: String? = null

    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private var imageAlpha = 1f
    private var fader: ValueAnimator? = null

    private val radius = t.dpf(VodSizes.CARD_RADIUS)
    private val ringPx = t.dpf(VodSizes.RING)
    private val box = RectF()
    private val ringBox = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val holderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = RING_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t.dpf(1f) }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.bold
        color = NAME_COLOR
        textSize = sp(12.5f)
    }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.semibold
        color = CAPTION_COLOR
        textSize = sp(10.5f)
    }
    private val holderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.semibold
        color = t.textMuted
        textSize = sp(12.5f)
    }
    private val seeAllPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.semibold
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.bold
        color = 0xFFFFFFFF.toInt()
        textSize = sp(10f)
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOR }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }

    // Text laid out once per title and size, never while drawing.
    private var holderLayout: Layout? = null
    private var nameLine: CharSequence = ""
    private var captionLine: CharSequence = ""
    private var textFor = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    /** The width and height its picture is decoded at: the grown card's, in px. */
    val decodeW: Int get() = ceil(t.dpf(if (kind == Kind.WIDE) VodSizes.WIDE_W else VodSizes.POSTER_W) * (1f + VodSizes.GROW)).toInt()
    val decodeH: Int get() = ceil(t.dpf(if (kind == Kind.WIDE) VodSizes.WIDE_H else VodSizes.POSTER_H) * (1f + VodSizes.GROW)).toInt()

    /** Shows [card]: only redrawn (its picture is bound separately, [bindImage]). */
    fun bind(card: Card) {
        if (card == this.card) return
        val samePicture = pictureOf(card) == this.card?.let { pictureOf(it) }
        this.card = card
        contentDescription = card.title.name
        textFor = -1
        if (!samePicture && imageKey != null) ImageLoader.release(this)
        invalidate()
    }

    /** The See all card. */
    fun bindSeeAll() {
        card = null
        contentDescription = "See all"
        invalidate()
    }

    /** Asks for its picture, at the grown size; nothing when it's bound already. */
    fun bindImage() {
        if (kind == Kind.SEE_ALL) return
        val c = card ?: return
        val url = pictureOf(c)
        if (url == null) {
            if (imageKey != null) ImageLoader.release(this)
            return
        }
        val key = ImageLoader.keyOf(url, decodeW, decodeH)
        if (imageKey == key) return
        ImageLoader.load(url, this, decodeW, decodeH)
    }

    /** Lets its picture go (the card shows its name until it's bound again). */
    fun releaseImage() {
        if (imageKey != null || bitmap != null) ImageLoader.release(this)
    }

    private fun pictureOf(c: Card): String? = if (kind == Kind.WIDE) wideArt(c) else c.title.poster

    override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
        fader?.cancel()
        fader = null
        this.bitmap = bitmap
        shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        if (bitmap != null && !fromMemory) {
            imageAlpha = 0f
            fader = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                addUpdateListener { imageAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            imageAlpha = 1f
        }
        invalidate()
    }

    /** The grid's chosen poster: its ring fades in or out (the poster grows separately). */
    fun animateFocus(on: Boolean) {
        focusFade?.cancel()
        focusFade = null
        val from = focus
        val to = if (on) 1f else 0f
        if (from == to) return
        focusFade = ValueAnimator.ofFloat(from, to).apply {
            duration = Tokens.FADE_MS
            addUpdateListener { focus = it.animatedValue as Float }
            start()
        }
    }

    private var focusFade: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        holderPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), HOLDER_TOP, HOLDER_BOTTOM, Shader.TileMode.CLAMP)
        scrimPaint.shader = LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), 0x00000000, SCRIM_BOTTOM, Shader.TileMode.CLAMP)
        textFor = -1
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        if (w <= 0) return
        if (kind == Kind.SEE_ALL) {
            drawSeeAll(canvas)
        } else {
            drawCard(canvas)
        }
        drawRing(canvas)
    }

    private fun drawCard(canvas: Canvas) {
        val c = card
        val bmp = bitmap
        val s = shader
        if (textFor != width) layOutText(c)
        if (bmp == null || imageAlpha < 1f) {
            canvas.drawRoundRect(box, radius, radius, holderPaint)
            if (kind == Kind.POSTER) holderLayout?.let { l ->
                canvas.save()
                canvas.translate((width - l.width) / 2f, (height - l.height) / 2f)
                l.draw(canvas)
                canvas.restore()
            }
        }
        if (bmp != null && s != null) {
            shaderMatrix.setScale(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            s.setLocalMatrix(shaderMatrix)
            imagePaint.shader = s
            imagePaint.alpha = (255 * imageAlpha).toInt()
            canvas.drawRoundRect(box, radius, radius, imagePaint)
        }
        if (kind == Kind.WIDE && c != null) drawWideText(canvas, c)
        c?.badge?.let { drawBadge(canvas, it) }
    }

    /** A wide card's bottom: a soft dark scrim, the name, the caption line and the progress bar. */
    private fun drawWideText(canvas: Canvas, c: Card) {
        canvas.drawRoundRect(box, radius, radius, scrimPaint)
        val side = t.dpf(8f)
        val progress = c.progress
        val bottom = height - if (progress != null) t.dpf(15f) else t.dpf(9f)
        val nameBaseline = if (captionLine.isNotEmpty()) {
            val captionBaseline = bottom - captionPaint.descent()
            canvas.drawText(captionLine, 0, captionLine.length, side, captionBaseline, captionPaint)
            // The name just above, 2 dp clear of the caption's tallest letters.
            captionBaseline + captionPaint.ascent() - t.dpf(2f) - namePaint.descent()
        } else {
            bottom - namePaint.descent()
        }
        canvas.drawText(nameLine, 0, nameLine.length, side, nameBaseline, namePaint)
        if (progress != null) {
            val top = height - t.dpf(6f) - t.dpf(3f)
            val r = t.dpf(1.5f)
            val right = width - side
            canvas.drawRoundRect(side, top, right, top + t.dpf(3f), r, r, trackPaint)
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) canvas.drawRoundRect(side, top, side + (right - side) * p, top + t.dpf(3f), r, r, barPaint)
        }
    }

    private fun drawBadge(canvas: Canvas, text: String) {
        val padX = t.dpf(7f)
        val padY = t.dpf(2f)
        val tw = badgePaint.measureText(text)
        val th = badgePaint.descent() - badgePaint.ascent()
        val right = width - t.dpf(6f)
        val top = t.dpf(6f)
        val r = t.dpf(8f)
        canvas.drawRoundRect(right - tw - 2 * padX, top, right, top + th + 2 * padY, r, r, badgeFill)
        canvas.drawText(text, right - tw - padX, top + padY - badgePaint.ascent(), badgePaint)
    }

    /**
     * See all: a flat fill in both looks (glass in a moving strip would show the wrong part of the wallpaper), white
     * with blue words when chosen.
     */
    private fun drawSeeAll(canvas: Canvas) {
        val f = focus
        val plain = if (Glass.on) SEE_ALL_GLASS else t.rowFill
        fillPaint.color = blend(plain, t.focusFill, f)
        canvas.drawRoundRect(box, radius, radius, fillPaint)
        if (Glass.on && f < 1f) {
            rimPaint.color = SEE_ALL_RIM
            rimPaint.alpha = (alphaOf(SEE_ALL_RIM) * (1f - f)).toInt()
            val half = rimPaint.strokeWidth / 2
            ringBox.set(half, half, width - half, height - half)
            canvas.drawRoundRect(ringBox, radius, radius, rimPaint)
        }
        seeAllPaint.color = blend(t.text, t.accent, f)
        val y = height / 2f - (seeAllPaint.descent() + seeAllPaint.ascent()) / 2f
        canvas.drawText(SEE_ALL, width / 2f, y, seeAllPaint)
    }

    private fun drawRing(canvas: Canvas) {
        val f = focus
        if (f <= 0f) return
        // As thick on screen whatever the card's growth: the view's scale applies to what it draws.
        val scale = if (scaleX > 0f) scaleX else 1f
        val stroke = ringPx / scale
        ringPaint.strokeWidth = stroke
        ringPaint.alpha = (255 * f).toInt()
        val half = stroke / 2f
        ringBox.set(-half, -half, width + half, height + half)
        canvas.drawRoundRect(ringBox, radius + half, radius + half, ringPaint)
    }

    private fun layOutText(c: Card?) {
        textFor = width
        val name = c?.title?.name.orEmpty()
        val avail = (width - 2 * t.dpf(8f)).coerceAtLeast(1f)
        if (kind == Kind.WIDE) {
            nameLine = TextUtils.ellipsize(name, namePaint, avail, TextUtils.TruncateAt.END)
            captionLine = TextUtils.ellipsize(c?.caption.orEmpty(), captionPaint, avail, TextUtils.TruncateAt.END)
            holderLayout = null
        } else {
            holderLayout = if (name.isEmpty()) null else makeLayout(name, avail.toInt())
        }
    }

    @Suppress("DEPRECATION")
    private fun makeLayout(text: String, w: Int): Layout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, holderTextPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, holderTextPaint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }

    private companion object {
        const val SEE_ALL = "See all ›"
        const val FADE_MS = 160L
        /** The white of the focus ring (the sizing page's focus white). */
        const val RING_COLOR = 0xFFF2F4F7.toInt()
        const val NAME_COLOR = 0xFFFFFFFF.toInt()
        const val CAPTION_COLOR = 0xFFD6DEE8.toInt()
        const val TRACK_COLOR = 0x47FFFFFF
        /** The Episodes sheet's card placeholder. */
        const val HOLDER_TOP = 0xFF26313F.toInt()
        const val HOLDER_BOTTOM = 0xFF141A22.toInt()
        const val SCRIM_BOTTOM = 0xC7000000.toInt()
        /** The settings windows' grouped-row fill and rim (group_fill_glass, group_rim_glass). */
        const val SEE_ALL_GLASS = 0x14FFFFFF
        const val SEE_ALL_RIM = 0x24FFFFFF

        fun alphaOf(c: Int) = c ushr 24

        /** [a] towards [b] by [f], channel by channel. */
        fun blend(a: Int, b: Int, f: Float): Int {
            if (f <= 0f) return a
            if (f >= 1f) return b
            fun ch(shift: Int): Int {
                val x = (a ushr shift) and 0xFF
                val y = (b ushr shift) and 0xFF
                return (x + (y - x) * f).toInt() and 0xFF
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/**
 * A wide card's picture: the title's backdrop at the wide cards' size (metahub `background/small` or TMDB `w780`, about
 * a third of the header's), else its poster.
 */
internal fun wideArt(c: Card): String? = VodArt.backgroundAt(c.title.background, VodArt.Size.SMALL) ?: c.title.poster
