package app.fireplayer.playback

/**
 * How long a channel takes to show, for the log (never a link or a name: only times and the kind of stream):
 * from the key press to the stream being asked for, to the player having enough to start, and to the first frame.
 * Pure: times are passed in (uptime ms).
 */
class LiveTiming {
    private var pressedAt = NONE
    private var openedAt = NONE
    private var readyAt = NONE
    private var kind = ""
    private var reused = false

    /** A stream is being opened at [now] for a key pressed at [pressed] (0 or less: not known, counted from [now]). */
    fun opened(now: Long, pressed: Long, kind: String, reusedPlayer: Boolean) {
        openedAt = now
        pressedAt = if (pressed in 1..now) pressed else now
        readyAt = NONE
        this.kind = kind
        reused = reusedPlayer
    }

    /** The player has enough loaded to start. */
    fun ready(now: Long) {
        if (openedAt != NONE && readyAt == NONE) readyAt = now
    }

    /** The first frame is on screen: the line to log, once per opened stream (null after that). */
    fun firstFrame(now: Long): String? {
        if (openedAt == NONE) return null
        val total = now - pressedAt
        val toOpen = openedAt - pressedAt
        val toReady = (if (readyAt == NONE) now else readyAt) - pressedAt
        openedAt = NONE
        return "Channel shown in $total ms (key to open $toOpen ms, ready at $toReady ms; $kind; player ${if (reused) "reused" else "new"})"
    }

    /** Nothing is being timed (the stream closed before it showed). */
    fun clear() {
        openedAt = NONE
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** The kind of stream by its address's ending, for the log: never the address itself. */
        fun kindOf(lastPathSegment: String?): String {
            val p = lastPathSegment?.lowercase() ?: return "stream"
            return when {
                p.endsWith(".m3u8") || p.endsWith(".m3u") -> "HLS"
                p.endsWith(".mpd") -> "DASH"
                p.endsWith(".ts") -> "TS"
                else -> "stream"
            }
        }
    }
}
