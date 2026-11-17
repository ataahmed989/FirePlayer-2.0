package app.fireplayer.live.ui

import app.fireplayer.player.PictureFit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the live picture's surface goes (HomeActivity lays the SurfaceView out with it). Pure arithmetic, so it's
 * unit-tested.
 *
 * The guide's miniature always fits the picture's shape inside its slot. Full screen follows Display size, the same
 * saved choice and the same meaning as in the VOD player ([PictureFit]: Normal, Fill screen, Zoom, Stretch, 16:9, 4:3,
 * 21:9), centred on the screen; Fill screen and Zoom make the surface bigger than the screen, so it starts above and
 * left of it (negative margins) and the screen crops it. Unlike the VOD player, black bars inside the stream itself
 * aren't measured for live channels: Fill screen and Stretch go by the whole frame.
 */
object LivePicture {

    /** The surface's place in the target's coordinates: left and top may be negative, the size bigger than the screen. */
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /**
     * The surface for a picture of [aspect] (width / height, pixel shape included; 0 or less while unknown) in the
     * target [left], [top], [width] x [height]: the whole screen when [full] (laid out for Display size [mode]), else
     * the miniature's slot (always fitted, 16:9 assumed while the shape is unknown).
     */
    fun place(left: Float, top: Float, width: Float, height: Float, aspect: Float, full: Boolean, mode: Int): Placement {
        if (full) {
            val fit = PictureFit.size(mode, if (aspect > 0f) aspect else 0f, width.roundToInt(), height.roundToInt())
            if (fit != null) {
                val (w, h) = fit
                return Placement(left.roundToInt() + (width.roundToInt() - w) / 2, top.roundToInt() + (height.roundToInt() - h) / 2, w, h)
            }
        }
        val a = if (aspect > 0f) aspect else 16f / 9f
        var w = width
        var h = w / a
        if (h > height) {
            h = height
            w = h * a
        }
        return Placement((left + (width - w) / 2).toInt(), (top + (height - h) / 2).toInt(), w.toInt(), h.toInt())
    }

    /**
     * The part of [p] on a screen of [screenW] x [screenH] (captions go there, so they stay readable when the picture
     * is bigger than the screen); [p] itself when it's all on screen.
     */
    fun onScreen(p: Placement, screenW: Int, screenH: Int): Placement {
        val l = max(p.left, 0)
        val t = max(p.top, 0)
        val r = min(p.right, screenW)
        val b = min(p.bottom, screenH)
        if (r <= l || b <= t) return p
        return Placement(l, t, r - l, b - t)
    }
}
