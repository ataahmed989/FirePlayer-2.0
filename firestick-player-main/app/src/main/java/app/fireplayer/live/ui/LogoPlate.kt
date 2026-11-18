package app.fireplayer.live.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A channel logo as decoded, with the plate it's drawn on (chosen once, as it loads: [LogoPlate.light]). */
class Logo(val bitmap: Bitmap, val light: Boolean)

/**
 * The plate behind every channel logo (guide rows, the details beside the miniature, the full-screen channel line),
 * so logos stand out from the dark wallpaper in both looks. Most logos sit on a soft see-through light plate: white
 * and coloured ones stay bright on it. Dark ink on a see-through background (a black or grey wordmark) would still
 * vanish there, so those get a light plate instead. Pure, unit-tested.
 */
object LogoPlate {
    /** White at 20%: over the dark wallpaper (or glass) a soft lighter tile; white logos keep their contrast. */
    const val SOFT = 0x33FFFFFF
    /** A light grey at 80%: dark ink reads on it as on paper. */
    const val LIGHT = 0xCCE6E8EB.toInt()

    /**
     * The darkest a logo's ink may be on average and still sit on [SOFT] (relative luminance, 0 black to 1 white).
     * About where dark ink reads equally badly on both plates: darker ink reads better on [LIGHT], lighter ink on [SOFT].
     */
    const val DARK_INK = 0.17f
    /** Logos covering at least this much of their box with their own background bring it with them: [SOFT] just frames them. */
    const val OWN_BACKGROUND = 0.9f

    fun color(light: Boolean): Int = if (light) LIGHT else SOFT

    /**
     * Whether a logo needs [LIGHT]: from its first [count] pixels ([argb], unpremultiplied, as Bitmap.getPixels gives
     * them), the average luminance of its ink (weighted by how opaque each pixel is), unless it has a background of its
     * own. A fully see-through logo has nothing to show either way: [SOFT].
     */
    fun light(argb: IntArray, count: Int = argb.size): Boolean {
        val n = minOf(count, argb.size)
        var ink = 0.0
        var lum = 0.0
        for (i in 0 until n) {
            val c = argb[i]
            val a = (c ushr 24) / 255.0
            if (a == 0.0) continue
            ink += a
            lum += a * (0.2126 * LINEAR[(c shr 16) and 0xFF] + 0.7152 * LINEAR[(c shr 8) and 0xFF] + 0.0722 * LINEAR[c and 0xFF])
        }
        if (ink <= 0.0) return false
        if (ink / n >= OWN_BACKGROUND) return false
        return lum / ink < DARK_INK
    }

    /** sRGB channel value to linear light, once for all 256 values. */
    private val LINEAR = DoubleArray(256) { v ->
        val s = v / 255.0
        if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
}

/**
 * Draws a logo on its plate inside a box: the plate fills the box (every logo gets the same tile, so a column of them
 * lines up), the logo is fitted inside it with a little room around. Its paints and rects are made once: drawing
 * allocates nothing (the guide draws one per row, every frame while it moves).
 */
class PlateDrawer(density: Float) {
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val fit = RectF()
    private val radius = 4f * density
    private val padX = 3f * density
    private val padY = 2f * density

    /** [logo] on its plate in the box [l], [t], [r], [b]; with no logo yet (on its way), the soft plate alone. */
    fun draw(canvas: Canvas, logo: Logo?, l: Float, t: Float, r: Float, b: Float) {
        box.set(l, t, r, b)
        plate.color = LogoPlate.color(logo != null && logo.light)
        canvas.drawRoundRect(box, radius, radius, plate)
        val bmp = logo?.bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0) return
        val w = r - l - 2 * padX
        val h = b - t - 2 * padY
        if (w <= 0f || h <= 0f) return
        val s = minOf(w / bmp.width, h / bmp.height)
        val bw = bmp.width * s
        val bh = bmp.height * s
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        fit.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        canvas.drawBitmap(bmp, null, fit, image)
    }
}

/**
 * A channel logo on its plate, for lines laid out once (the details' top line, the full-screen channel line): a new
 * logo only redraws it, nothing is laid out again (an ImageView lays out again whenever its picture's size changes).
 */
class LogoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val drawer = PlateDrawer(resources.displayMetrics.density)
    private var logo: Logo? = null
    private var plate = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [logo] on its plate; [plate] without a logo shows the plate alone (a logo on its way); neither draws nothing. */
    fun set(logo: Logo?, plate: Boolean = logo != null) {
        val show = plate || logo != null
        if (logo === this.logo && show == this.plate) return
        this.logo = logo
        this.plate = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!plate) return
        drawer.draw(canvas, logo, 0f, 0f, width.toFloat(), height.toFloat())
    }
}
