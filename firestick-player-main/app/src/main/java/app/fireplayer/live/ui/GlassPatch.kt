package app.fireplayer.live.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.fireplayer.ui.GlassStyle
import kotlin.math.ceil

/**
 * What one kind of [GlassPatch] is made of, all from the shared browsing style ([GlassStyle]): [coats] of [fill], the
 * faint light from above ([sheen]) and the outline ([edge]: the hairline all round and [highlight] along the top).
 */
internal class PatchLook(
    val fill: Int,
    val coats: Int = 1,
    val sheen: Boolean = false,
    val edge: Boolean = true,
    val highlight: Int = GlassStyle.HIGHLIGHT,
) {
    companion object {
        /** A programme cell: barely lighter than the page behind it, a hairline round it, a gentle top highlight. */
        val CELL = PatchLook(GlassStyle.ROW_FILL, highlight = GlassStyle.ROW_HIGHLIGHT)
        /** The cell the focus rests on while the groups column has it: a second coat, so a shade lighter. */
        val CELL_SELECTED = PatchLook(GlassStyle.ROW_FILL, coats = 2, highlight = GlassStyle.ROW_HIGHLIGHT)
        /** A row whose listings aren't loaded yet: the fill alone, no outline. */
        val CELL_EMPTY = PatchLook(GlassStyle.ROW_FILL, edge = false)
        /** A channel logo's tile: the glass of the app's buttons, dark under white and coloured logos. */
        val TILE = PatchLook(GlassStyle.CONTROL_TINT, sheen = true)
        /** The tile behind dark ink: the same glass frosted light ([LogoPlate.GLASS_LIGHT]). */
        val TILE_LIGHT = PatchLook(LogoPlate.GLASS_LIGHT, sheen = true)
    }
}

/** Where a [GlassPatch] is cut and placed (pure, unit-tested). */
internal object PatchMath {
    /** The pixels of middle kept in the drawing: every column of a patch's middle is the same, so a few do. */
    const val MID = 4

    /** A corner's radius in a patch [h] px tall asked to be [radius]: never more than half its height. */
    fun radius(radius: Float, h: Int): Float = minOf(radius, h / 2f).coerceAtLeast(0f)

    /** How wide each end of the drawing is, px: the whole corner and a pixel of the straight edge after it. */
    fun cap(radius: Float): Int = ceil(radius.toDouble()).toInt() + 1

    /** The drawing's width for ends [cap] wide. */
    fun width(cap: Int): Int = 2 * cap + MID

    /** Whether a shape [w] px wide has room for both ends ([cap] each); a narrower one is drawn plain. */
    fun fits(w: Int, cap: Int): Boolean = w >= 2 * cap

    /** The whole pixel nearest [v]: a patch's edges sit on pixels, so its hairline is one crisp pixel. */
    fun snap(v: Float): Int = Math.round(v)

    /**
     * The pixel height of a patch asked to be [size] px tall when one [baked] px tall is already drawn (0: none): the
     * drawn one while it's within half a pixel, so a size that sits right on a half pixel (and wobbles either side of
     * it as the rows move) never has the patch drawn again frame after frame.
     */
    fun height(size: Float, baked: Int): Int =
        if (baked > 0 && Math.abs(size - baked) <= 0.51f) baked else snap(size)
}

/**
 * A piece of the browsing glass ([GlassStyle]) the guide draws many times a frame: a programme cell, a channel logo's
 * tile. It's a surface in the page: its tint, hairline and highlight over whatever is behind it (the page's wallpaper,
 * already soft; a window's glass), so nothing is copied or blurred for it. The shape is drawn once, into a small
 * bitmap of its two ends and a few pixels of its middle, and then stamped (left end, the middle stretched, right end)
 * on whole pixels. Stamping allocates nothing, and a patch is drawn again only when its height changes.
 *
 * One patch is one look at one height at a time: something drawn at two heights uses two patches.
 */
internal class GlassPatch(
    private val density: Float,
    private val look: PatchLook,
    /** The corner radius, px, for a patch this many px tall. */
    private val radiusFor: (h: Int) -> Float,
) {
    private val stamp = Paint()
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.fill }
    private val src = Rect()
    private val dst = Rect()
    private val box = RectF()
    private var bitmap: Bitmap? = null
    private var radius = 0f
    private var cap = 0

    /** The glass in [l], [t], [r], [b], its edges on the nearest whole pixels. */
    fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val x0 = PatchMath.snap(l)
        val x1 = PatchMath.snap(r)
        val y0 = PatchMath.snap(t)
        // The height from the size, not from both edges: it stays the same pixel count while the patch moves.
        val baked = bitmap
        val h = PatchMath.height(b - t, baked?.height ?: 0)
        val w = x1 - x0
        if (w <= 0 || h <= 0) return
        val bmp = if (baked != null && baked.height == h) baked else bake(h)
        if (!PatchMath.fits(w, cap)) {
            // A sliver (a programme of a few minutes): no room for the ends, so the fill alone.
            box.set(x0.toFloat(), y0.toFloat(), x1.toFloat(), (y0 + h).toFloat())
            for (i in 0 until look.coats) canvas.drawRoundRect(box, radius, radius, plain)
            return
        }
        val y1 = y0 + h
        src.set(0, 0, cap, h)
        dst.set(x0, y0, x0 + cap, y1)
        canvas.drawBitmap(bmp, src, dst, stamp)
        if (w > 2 * cap) {
            src.set(cap, 0, cap + PatchMath.MID, h)
            dst.set(x0 + cap, y0, x1 - cap, y1)
            canvas.drawBitmap(bmp, src, dst, stamp)
        }
        src.set(cap + PatchMath.MID, 0, bmp.width, h)
        dst.set(x1 - cap, y0, x1, y1)
        canvas.drawBitmap(bmp, src, dst, stamp)
    }

    /** The patch [h] px tall, drawn as the shared style draws its surfaces: tint, sheen, hairline, top highlight. */
    private fun bake(h: Int): Bitmap {
        val r = PatchMath.radius(radiusFor(h), h)
        radius = r
        cap = PatchMath.cap(r)
        val w = PatchMath.width(cap)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = look.fill
        for (i in 0 until look.coats) c.drawRoundRect(rect, r, r, p)
        if (look.sheen) {
            p.shader = LinearGradient(0f, 0f, 0f, h * GlassStyle.SHEEN_SHARE, GlassStyle.SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, p)
            p.shader = null
        }
        if (look.edge) {
            val hair = GlassStyle.hairlinePx(density)
            val half = hair / 2f
            p.style = Paint.Style.STROKE
            p.strokeWidth = hair
            p.color = GlassStyle.HAIRLINE
            val inner = RectF(rect).apply { inset(half, half) }
            val ir = (r - half).coerceAtLeast(0f)
            c.drawRoundRect(inner, ir, ir, p)
            // The highlight follows the top edge round the corners: the shape less itself moved down a little.
            val line = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
            val lower = Path().apply {
                addRoundRect(RectF(rect).apply { offset(0f, GlassStyle.HIGHLIGHT_DP * density) }, r, r, Path.Direction.CW)
            }
            if (line.op(lower, Path.Op.DIFFERENCE)) {
                p.style = Paint.Style.FILL
                p.color = look.highlight
                c.drawPath(line, p)
            }
        }
        bitmap = bmp
        return bmp
    }
}
