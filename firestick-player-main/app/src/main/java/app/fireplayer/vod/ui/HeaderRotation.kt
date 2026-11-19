package app.fireplayer.vod.ui

/**
 * When the page header moves on to its next title (pure, unit-tested; the clock is passed in). While idle it goes
 * through the page's feed (Top 10, or Popular's first 10) every [everyMs], round and round. It holds still while
 * paused (the page hidden, the header scrolled away, a window open), while it follows the chosen title of the
 * page's Continue row and while it's held (focus on one of the banner's buttons); when any of them ends, the full
 * wait starts again.
 */
class HeaderRotation(private val everyMs: Long = EVERY_MS) {

    /** Titles in the feed. */
    var count = 0
        private set

    /** The feed's title shown while rotating. */
    var index = 0
        private set

    var paused = true
        private set

    var following = false
        private set

    /** Focus is on one of the banner's buttons: the title shown stays. */
    var held = false
        private set

    private var nextAt = NEVER

    /** Moving on by itself: a feed of two or more, not paused, not following, not held. */
    val running: Boolean get() = !paused && !following && !held && count > 1

    /** A new feed of [count] titles. The title shown stays when it still exists; the wait starts again. */
    fun setFeed(count: Int, now: Long, keepIndex: Boolean = true) {
        this.count = count.coerceAtLeast(0)
        if (!keepIndex || index >= this.count) index = 0
        reschedule(now)
    }

    fun pause() {
        paused = true
        nextAt = NEVER
    }

    fun resume(now: Long) {
        paused = false
        reschedule(now)
    }

    /** Focus is in the page's Continue row: the header shows that title instead. */
    fun follow() {
        following = true
        nextAt = NEVER
    }

    /** Focus left the Continue row: back to the feed's title, the wait starting again. */
    fun unfollow(now: Long) {
        following = false
        reschedule(now)
    }

    /** Focus is on one of the banner's buttons: it stays on the title shown. */
    fun hold() {
        held = true
        nextAt = NEVER
    }

    /** Focus left the banner's buttons: the full wait starts again. */
    fun unhold(now: Long) {
        if (!held) return
        held = false
        reschedule(now)
    }

    /** Shows feed title [i] now; the full wait starts again. */
    fun jumpTo(i: Int, now: Long) {
        if (count <= 0) return
        index = i.coerceIn(0, count - 1)
        reschedule(now)
    }

    /** The full wait starts again (the turn came while a window was open over the page). */
    fun restart(now: Long) = reschedule(now)

    /** Milliseconds until the next title, or -1 when it isn't moving on by itself. */
    fun delayFrom(now: Long): Long = if (!running || nextAt == NEVER) -1 else (nextAt - now).coerceAtLeast(0)

    /** Moves on when it's time: true when [index] changed. */
    fun advanceIfDue(now: Long): Boolean {
        if (!running || now < nextAt) return false
        index = (index + 1) % count
        nextAt = now + everyMs
        return true
    }

    /** The title after the one shown (to fetch its picture ahead), or -1. */
    fun nextIndex(): Int = if (count > 1) (index + 1) % count else -1

    private fun reschedule(now: Long) {
        nextAt = if (running) now + everyMs else NEVER
    }

    companion object {
        /** Glass Home's hero: a new title every 8 s. */
        const val EVERY_MS = 8_000L
        private const val NEVER = Long.MAX_VALUE
    }
}
