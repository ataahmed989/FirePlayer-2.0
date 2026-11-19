package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTimingTest {

    @Test fun fromTheKeyPressToTheFirstFrame() {
        val t = LiveTiming()
        t.opened(now = 1_012, pressed = 1_000, kind = "TS", reusedPlayer = true)
        t.ready(1_600)
        assertEquals("Channel shown in 850 ms (key to open 12 ms, ready at 600 ms; TS; player reused)", t.firstFrame(1_850))
    }

    @Test fun loggedOncePerStream() {
        val t = LiveTiming()
        assertNull(t.firstFrame(5))
        t.opened(100, 100, "HLS", false)
        t.firstFrame(900)
        assertNull(t.firstFrame(950))
    }

    @Test fun withoutAKeyPressItCountsFromTheOpen() {
        val t = LiveTiming()
        t.opened(now = 500, pressed = 0, kind = "stream", reusedPlayer = false)
        assertEquals("Channel shown in 300 ms (key to open 0 ms, ready at 300 ms; stream; player new)", t.firstFrame(800))
        // A press "after" the open (a stale value) is not believed either.
        t.opened(now = 500, pressed = 900, kind = "stream", reusedPlayer = false)
        assertEquals("Channel shown in 100 ms (key to open 0 ms, ready at 100 ms; stream; player new)", t.firstFrame(600))
    }

    @Test fun aStreamClosedBeforeItShowedLogsNothing() {
        val t = LiveTiming()
        t.opened(100, 100, "TS", true)
        t.clear()
        assertNull(t.firstFrame(400))
    }

    @Test fun theKindOfStreamNeverTheAddress() {
        assertEquals("HLS", LiveTiming.kindOf("index.M3U8"))
        assertEquals("DASH", LiveTiming.kindOf("manifest.mpd"))
        assertEquals("TS", LiveTiming.kindOf("1234.ts"))
        assertEquals("stream", LiveTiming.kindOf("1234"))
        assertEquals("stream", LiveTiming.kindOf(null))
    }
}
