package app.fireplayer.player

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Room made for a film is given back to the TV at once, and the film is told when it's there.
 *
 * What FirePlayer lets go of for a film (the pages' pictures and caches as the player covers them, the buffer of the
 * film or episode before) is only memory nobody points to any more: Android takes it back the next time it collects,
 * which it does when the app has made enough new things, not when something big was let go of. Until then the TV
 * counts it as in use. A film's buffer is sized by the memory free as its player is built ([FilmMemory.plan]), so one
 * built just then was held down for memory that was already on its way back: after Next episode, with the last
 * episode's buffer (up to 128 MiB on a Fire TV Stick 4K Max) not yet collected, and at every start from a page, whose
 * pictures go only once the player's screen is up.
 *
 * [made] has Android collect now, on a thread of its own at background priority (the app's other threads carry on;
 * they're held for a millisecond or two), and [onMade] is told a moment after, when what the pictures held outside
 * the app's own heap has been freed as well. The film's player then reads the free memory again
 * ([FilmMemory.afterRoom]).
 *
 * The same goes for a film's buffer squeezed while the TV is short of memory: what it gives back is collected at
 * once, and as it comes down to its new size ([FilmMemory.comingDown]), so the TV has it when it needs it.
 */
object FilmRoom {
    private const val TAG = "FirePlayer"
    /** After collecting, the pictures' own memory is freed by Android's clean-up thread: read the free memory after it. */
    const val SETTLE_MS = 400L

    private val main = Handler(Looper.getMainLooper())

    // One collection at a time, and at most one more waiting (asked for while the first ran: it may have let go of
    // more since); any further ask meanwhile is covered by the one waiting.
    private val worker = ThreadPoolExecutor(
        0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "FirePlayerRoom").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private val told = Runnable { onMade?.invoke() }

    /**
     * Told on the main thread once memory let go of is back with the TV. The film player on screen sets it, and takes
     * it away again when its player goes (only its own: the next player may have set its own meanwhile).
     */
    var onMade: (() -> Unit)? = null

    /** Something big was just let go of (main thread): Android collects it now, and [onMade] hears when it's back. */
    fun made() {
        worker.execute {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                // Not System.gc(): Android's may put it off until finalizers have run.
                Runtime.getRuntime().gc()
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't collect memory let go of: ${e.javaClass.simpleName}")
            }
            Log.i(TAG, "Memory let go of collected in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            main.removeCallbacks(told)
            main.postDelayed(told, SETTLE_MS)
        }
    }
}
