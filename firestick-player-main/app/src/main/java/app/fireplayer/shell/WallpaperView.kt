package app.fireplayer.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import app.fireplayer.R
import app.fireplayer.ui.StillGlass
import java.util.concurrent.Executors

/**
 * The stationary background shared by every tab in both looks (design-layout: one blue fog background, independent of
 * the featured title). It is decoded and scaled once, off the main thread, and the same pass builds the blurred
 * pictures glass shows ([StillGlass]), so glass on these screens never copies or blurs anything per frame.
 *
 * Live video shows through a [hole]: the guide's miniature, or the whole screen in full screen. The live picture is a
 * SurfaceView behind the window, so everything drawn here stays outside the hole; its rounded corners are the
 * wallpaper drawn over the corners of the video.
 */
class WallpaperView(context: Context) : View(context) {

    private var picture: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val holeRect = RectF()
    private var holeRadius = 0f
    private val holePath = Path()
    private var fullHole = false
    private val dst = Rect()

    /** Called once the wallpaper and its glass are ready (main thread). */
    var onReady: ((StillGlass) -> Unit)? = null

    init {
        // No background: a background ignores the hole and would cover the video.
        setWillNotDraw(false)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Where live video shows: [rect] in this view's coordinates; null for none; [full] for the whole view. */
    fun setHole(rect: RectF?, radius: Float, full: Boolean = false) {
        fullHole = full
        if (rect == null) holeRect.setEmpty() else holeRect.set(rect)
        holeRadius = radius
        holePath.reset()
        if (!holeRect.isEmpty) holePath.addRoundRect(holeRect, radius, radius, Path.Direction.CW)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && picture == null) load(w, h)
    }

    private fun load(w: Int, h: Int) {
        val res = resources
        val density = res.displayMetrics.density
        worker.execute {
            val decoded = BitmapFactory.decodeResource(res, R.drawable.wallpaper_blue_fog) ?: return@execute
            // Centre-cropped to the screen once, so drawing is a plain copy.
            val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sx = w.toFloat() / decoded.width
            val sy = h.toFloat() / decoded.height
            val s = maxOf(sx, sy)
            val dw = decoded.width * s
            val dh = decoded.height * s
            Canvas(scaled).drawBitmap(decoded, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            val glass = StillGlass.from(scaled, w, h, density)
            main.post {
                picture = scaled
                invalidate()
                onReady?.invoke(glass)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (fullHole) return
        val save = canvas.save()
        if (!holeRect.isEmpty) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipOutPath(holePath)
            else @Suppress("DEPRECATION") canvas.clipPath(holePath, Region.Op.DIFFERENCE)
        }
        val bmp = picture
        if (bmp == null) {
            canvas.drawColor(Color.BLACK)
        } else {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(bmp, null, dst, paint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
    }

    private val main = Handler(Looper.getMainLooper())

    private companion object {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerWallpaper").apply { priority = Thread.MIN_PRIORITY } }
    }
}
