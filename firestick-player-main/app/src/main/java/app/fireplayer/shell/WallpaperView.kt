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
 * The stationary background shared by every tab (independent of the featured title): the blue fog with the glass
 * look, and the user's dark background with the classic look ([glassLook]). It is decoded and scaled once per look,
 * off the main thread; with glass, the same pass builds the blurred pictures glass shows ([StillGlass]), so glass on
 * these screens never copies or blurs anything per frame.
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

    /** Called once the glass look's wallpaper and its glass are ready (main thread). */
    var onReady: ((StillGlass) -> Unit)? = null

    /** Which background: the glass look's blue fog (true) or the classic look's own (false). */
    var glassLook: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            // The other picture loads; the one on screen stays until it's ready, so nothing flashes black.
            if (width > 0 && height > 0) load(width, height)
        }

    private var loadSerial = 0

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
        val glassLook = glassLook
        val serial = ++loadSerial
        worker.execute {
            val decoded = BitmapFactory.decodeResource(res, if (glassLook) R.drawable.wallpaper_blue_fog else R.drawable.wallpaper_classic) ?: return@execute
            // Centre-cropped to the screen once, so drawing is a plain copy.
            val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sx = w.toFloat() / decoded.width
            val sy = h.toFloat() / decoded.height
            val s = maxOf(sx, sy)
            val dw = decoded.width * s
            val dh = decoded.height * s
            Canvas(scaled).drawBitmap(decoded, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            // Only the glass look shows blurred wallpaper.
            val glass = if (glassLook) StillGlass.from(scaled, w, h, density) else null
            main.post {
                if (serial != loadSerial) return@post
                picture = scaled
                invalidate()
                glass?.let { onReady?.invoke(it) }
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
