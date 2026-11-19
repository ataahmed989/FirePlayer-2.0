package app.fireplayer.player

import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log

/**
 * Has Android let go of the graphics memory FirePlayer's windows no longer use: the drawing cache's working pictures
 * (layers that windows and rows slid in on, glyphs, pictures drawn once), as Android does itself when an app's screens
 * are hidden. On a Fire TV Stick 4K Max, 41 MB of such pictures sat unused in FirePlayer during a 4K film that needed
 * the memory. What's on screen stays; what's needed again is made again as it's drawn (a few milliseconds).
 *
 * The call isn't public, so it's made by name and skipped wherever it isn't there. Main thread.
 */
object GraphicsTrim {
    private const val TAG = "FirePlayer"
    private var missing = false
    private var lastAt = 0L

    /**
     * Trims now; [minGapMs] apart at least (0: always), so it can be asked for each time the controls hide. True when
     * the trim was made.
     */
    fun trim(minGapMs: Long = 0L): Boolean {
        if (missing) return false
        val now = SystemClock.elapsedRealtime()
        if (minGapMs > 0L && lastAt != 0L && now - lastAt < minGapMs) return false
        lastAt = now
        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            global.getMethod("trimMemory", Int::class.javaPrimitiveType).invoke(instance, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            true
        } catch (e: Throwable) {
            missing = true
            Log.i(TAG, "Graphics trim not available: ${e.javaClass.simpleName}")
            false
        }
    }
}
