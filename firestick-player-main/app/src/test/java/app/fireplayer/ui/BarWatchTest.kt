package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** How often the black-bar watch copies the film. */
class BarWatchTest {

    @Test fun looksComeHalfAsOftenWithHeavyVideo() {
        assertEquals(1_000L, BarWatch.gapMs(known = false, heavy = false))
        assertEquals(4_000L, BarWatch.gapMs(known = true, heavy = false))
        assertEquals(2_000L, BarWatch.gapMs(known = false, heavy = true))
        assertEquals(8_000L, BarWatch.gapMs(known = true, heavy = true))
    }
}
