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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget

/**
 * The header's backdrop: the title's background picture in a box at the card's right, decoded at half the size it's
 * shown at, its left and bottom fading to clear baked in once on the image thread (so the card's own surface shows
 * through and nothing is blurred or masked per frame). A new title's picture crossfades in over the old one (650 ms,
 * Glass Home's hero fade): one view redrawn, nothing laid out.
 */
@SuppressLint("ViewConstructor")
class HeaderArt(context: Context) : View(context), ImageTarget {

    override var imageKey: String? = null

    /** The card's corner radius, so the picture follows the card's rounded right edge. */
    var radius = 0f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private var shown: Bitmap? = null
    private var shownShader: BitmapShader? = null
    private var before: Bitmap? = null
    private var beforeShader: BitmapShader? = null
    private var fade = 1f
    private var fader: ValueAnimator? = null
    private val box = RectF()
    private val m = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wanted: String? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The decode size: half the shown size. */
    private val decodeW: Int get() = (width / 2).coerceAtLeast(1)
    private val decodeH: Int get() = (height / 2).coerceAtLeast(1)

    /** Shows [url]'s picture (crossfading from what shows now), or nothing for null. */
    fun show(url: String?) {
        wanted = url
        if (width <= 0 || height <= 0) return
        if (url == null) {
            ImageLoader.release(this)
            return
        }
        ImageLoader.load(url, this, decodeW, decodeH, Bitmap.Config.ARGB_8888, BAKE, KEY_SUFFIX, CROP_Y)
    }

    /** Decodes [url]'s picture ahead (the next title), so its switch never waits. */
    fun warm(url: String?) {
        if (width <= 0 || height <= 0) return
        ImageLoader.warm(url, decodeW, decodeH, Bitmap.Config.ARGB_8888, BAKE, KEY_SUFFIX, CROP_Y)
    }

    /** Lets both pictures go (the page is hidden). */
    fun release() {
        fader?.cancel()
        ImageLoader.release(this)
        shown = null; shownShader = null
        before = null; beforeShader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        if (w > 0 && h > 0 && wanted != null && imageKey == null) show(wanted)
    }

    override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
        // Cleared while the next one is fetched: the old picture stays until the new one crossfades over it.
        if (bitmap == null) {
            if (imageKey == null) {
                fader?.cancel()
                shown = null; shownShader = null
                before = null; beforeShader = null
                invalidate()
            }
            return
        }
        if (bitmap === shown) return
        fader?.cancel()
        before = shown
        beforeShader = shownShader
        shown = bitmap
        shownShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (before == null) {
            fade = 1f
            invalidate()
            return
        }
        fade = 0f
        fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) {
                    before = null
                    beforeShader = null
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = before
        if (b != null && fade < 1f) draw(canvas, b, beforeShader, 1f - fade)
        val s = shown ?: return
        draw(canvas, s, shownShader, fade)
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, shader: BitmapShader?, alpha: Float) {
        shader ?: return
        m.setScale(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(box, radius, radius, paint)
    }

    companion object {
        private const val FADE_MS = 650L
        private const val KEY_SUFFIX = "|fade"
        /** Faces sit high in most backdrops: the crop keeps a little more of the top. */
        private const val CROP_Y = 0.4f

        /**
         * The fade baked into a backdrop (image thread): clear at its left edge to solid over its left 45% (eased),
         * and solid to clear over its bottom 35%.
         */
        val BAKE: (Bitmap) -> Bitmap = { src ->
            val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            p.shader = LinearGradient(
                0f, 0f, w * 0.45f, 0f,
                intArrayOf(0x00000000, 0x24000000, 0x80000000.toInt(), 0xDB000000.toInt(), 0xFF000000.toInt()),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, p)
            p.shader = LinearGradient(0f, h * 0.65f, 0f, h, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            out
        }
    }
}
