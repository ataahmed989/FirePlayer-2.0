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
 * A title's backdrop, as Films & Shows' headers show it: the title's background picture decoded small (half the size
 * it's shown at), its left and bottom fading to clear baked in once on the image thread ([ArtSpec.bake]), so whatever
 * is under it shows through and nothing is blurred or masked per frame. A new title's picture crossfades in over the
 * old one ([ArtSpec.fadeMs]): one view redrawn, nothing laid out.
 *
 * Two uses, one part: the page header's box at the card's right ([ArtSpec.HEADER]: 650 ms, Glass Home's hero fade) and
 * the title page's full-screen backdrop ([ArtSpec.TITLE]: decoded at 960 x 540 px, drawn over the whole screen
 * ([frame]) and shown first from a small copy ([hold], [preview]) that the real one crossfades over in 140 ms).
 */
@SuppressLint("ViewConstructor")
class HeaderArt(context: Context, private val spec: ArtSpec = ArtSpec.HEADER) : View(context), ImageTarget {

    /**
     * How a backdrop is decoded and shown: [decodeW] x [decodeH] px (0: half the view's size), the fade baked into it,
     * the memory key's suffix for that bake, where the crop sits ([cropY]) and the crossfade's length.
     */
    class ArtSpec(
        val decodeW: Int,
        val decodeH: Int,
        val bake: (Bitmap) -> Bitmap,
        val keySuffix: String,
        val cropY: Float,
        val fadeMs: Long,
    ) {
        companion object {
            /** Step 1's page headers: clear at the left edge to solid over 45% (eased), solid to clear over the bottom 35%. */
            val HEADER = ArtSpec(
                0, 0,
                fade(0f, VodSizes.HEADER_FADE_LEFT_TO, VodSizes.HEADER_FADE_BOTTOM_FROM, 1f),
                "|fade", VodSizes.ART_CROP_Y, VodSizes.HERO_FADE_MS,
            )

            /** The title page's backdrop (design-brief.md §6.4): half the screen's pixels, the title page's fade. */
            val TITLE = ArtSpec(
                VodSizes.TITLE_ART_W, VodSizes.TITLE_ART_H,
                fade(VodSizes.TITLE_FADE_LEFT_FROM, VodSizes.TITLE_FADE_LEFT_TO, VodSizes.TITLE_FADE_BOTTOM_FROM, VodSizes.TITLE_FADE_BOTTOM_TO),
                "|title", VodSizes.ART_CROP_Y, VodSizes.TITLE_ART_FADE_MS,
            )

            /** The title page's small copy, shown until its backdrop comes: the same fade. */
            val TITLE_PREVIEW = ArtSpec(
                VodSizes.TITLE_PREVIEW_W, VodSizes.TITLE_PREVIEW_H, TITLE.bake, "|title", VodSizes.ART_CROP_Y, 0L,
            )

            /**
             * The fade baked into a backdrop (image thread): clear at [leftFrom] of its width to solid at [leftTo]
             * (eased: [VodSizes.FADE_ALPHAS] at [VodSizes.FADE_STOPS]), and solid at [bottomFrom] of its height to clear
             * at [bottomTo].
             */
            fun fade(leftFrom: Float, leftTo: Float, bottomFrom: Float, bottomTo: Float): (Bitmap) -> Bitmap = { src ->
                val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
                val w = out.width.toFloat()
                val h = out.height.toFloat()
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                p.shader = LinearGradient(
                    w * leftFrom, 0f, w * leftTo, 0f,
                    IntArray(VodSizes.FADE_ALPHAS.size) { VodSizes.FADE_ALPHAS[it] shl 24 },
                    VodSizes.FADE_STOPS,
                    Shader.TileMode.CLAMP,
                )
                c.drawRect(0f, 0f, w, h, p)
                p.shader = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
                out
            }
        }
    }

    override var imageKey: String? = null

    /** The card's corner radius, so the picture follows the card's rounded right edge. */
    var radius = 0f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /**
     * Where the picture is drawn, in this view's coordinates; null: the view itself. The title page draws its
     * backdrop over the whole screen, so the part under the rail's strip lies left of the view.
     */
    var frame: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    private var shown: Bitmap? = null
    private var shownShader: BitmapShader? = null
    /** The picture shown is a stand-in of another shape (drawn to cover, not stretched). */
    private var shownCover = false
    private var before: Bitmap? = null
    private var beforeShader: BitmapShader? = null
    private var beforeCover = false
    private var fade = 1f
    private var fader: ValueAnimator? = null
    private val box = RectF()
    private val m = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wanted: String? = null

    /** The small copy's own target: what it brings shows only until the real picture is there. */
    private val previewTarget = object : ImageTarget {
        override var imageKey: String? = null
        override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
            if (bitmap != null && shown == null) hold(bitmap, cover = false)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The decode size: the spec's, or half the shown size. */
    private val decodeW: Int get() = if (spec.decodeW > 0) spec.decodeW else (width / 2).coerceAtLeast(1)
    private val decodeH: Int get() = if (spec.decodeH > 0) spec.decodeH else (height / 2).coerceAtLeast(1)

    /** The memory key [url]'s picture has here (to find it decoded already, or drop it). */
    fun keyFor(url: String): String = ImageLoader.keyOf(url, decodeW, decodeH, spec.keySuffix)

    /** Shows [url]'s picture (crossfading from what shows now), or nothing for null. */
    fun show(url: String?) {
        wanted = url
        if (width <= 0 || height <= 0) return
        if (url == null) {
            ImageLoader.release(this)
            return
        }
        ImageLoader.load(url, this, decodeW, decodeH, Bitmap.Config.ARGB_8888, spec.bake, spec.keySuffix, spec.cropY)
    }

    /** Decodes [url]'s picture ahead (the next title), so its switch never waits. */
    fun warm(url: String?) {
        if (width <= 0 || height <= 0) return
        ImageLoader.warm(url, decodeW, decodeH, Bitmap.Config.ARGB_8888, spec.bake, spec.keySuffix, spec.cropY)
    }

    /**
     * Shows [bitmap] at once, until the real picture comes and crossfades over it: a smaller copy already in memory.
     * [cover]: it has another shape (a page header's crop), so it fills the frame, cut to fit, rather than stretched.
     */
    fun hold(bitmap: Bitmap, cover: Boolean) {
        if (shown != null) return
        shown = bitmap
        shownShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shownCover = cover
        fade = 1f
        invalidate()
    }

    /** A small copy of the picture ([ArtSpec.titlePreview]) from disk only, shown until the real one comes. */
    fun preview(url: String?, preview: ArtSpec) {
        if (url == null || shown != null) return
        ImageLoader.load(
            url, previewTarget, preview.decodeW, preview.decodeH, Bitmap.Config.ARGB_8888, preview.bake,
            preview.keySuffix, preview.cropY, diskOnly = true,
        )
    }

    /** Lets both pictures go (the page is hidden). */
    fun release() {
        fader?.cancel()
        ImageLoader.release(this)
        ImageLoader.release(previewTarget)
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
        // The small copy has done its part.
        ImageLoader.release(previewTarget)
        fader?.cancel()
        before = shown
        beforeShader = shownShader
        beforeCover = shownCover
        shown = bitmap
        shownShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shownCover = false
        if (before == null) {
            fade = 1f
            invalidate()
            return
        }
        fade = 0f
        fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = spec.fadeMs
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
        if (b != null && fade < 1f) draw(canvas, b, beforeShader, 1f - fade, beforeCover)
        val s = shown ?: return
        draw(canvas, s, shownShader, fade, shownCover)
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, shader: BitmapShader?, alpha: Float, cover: Boolean) {
        shader ?: return
        val f = frame ?: box
        val sx = f.width() / bmp.width.toFloat()
        val sy = f.height() / bmp.height.toFloat()
        if (cover) {
            // Cut to fill the frame, its crop placed as the real picture's is.
            val s = maxOf(sx, sy)
            m.setScale(s, s)
            m.postTranslate(f.left + (f.width() - bmp.width * s) / 2f, f.top + (f.height() - bmp.height * s) * spec.cropY)
        } else {
            m.setScale(sx, sy)
            m.postTranslate(f.left, f.top)
        }
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(f, radius, radius, paint)
    }
}
