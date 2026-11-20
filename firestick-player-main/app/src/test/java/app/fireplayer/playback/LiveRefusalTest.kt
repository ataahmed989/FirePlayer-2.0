package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRefusalTest {

    @Test fun fourHundredsRefuseButTimeoutsAndTooManyDoNot() {
        for (c in listOf(400, 401, 403, 404, 410, 451)) assertTrue("$c", LiveRefusal.refuses(c))
        for (c in listOf(0, 200, 302, 408, 429, 500, 502, 503, 509)) assertFalse("$c", LiveRefusal.refuses(c))
    }

    @Test fun aChannelThatNeverPlayedIsSaidAtTheFirstRefusal() {
        val r = LiveRefusal()
        r.opened()
        assertTrue(r.failed(404))
        assertEquals("This channel isn't available right now\nThe provider answered 404", r.message())
    }

    @Test fun theMeasuredCaseOneTimeoutThenRefusals() {
        val r = LiveRefusal()
        r.opened()
        assertFalse(r.failed(0)) // connect timeout: still "Reconnecting..."
        assertNull(r.message())
        repeat(7) { assertTrue(r.failed(404)) }
    }

    @Test fun aChannelThatPlayedNeedsTwoRefusalsInARow() {
        val r = LiveRefusal()
        r.opened()
        r.playing()
        assertFalse(r.failed(403))
        assertTrue(r.failed(403))
        assertEquals(403, r.code)
    }

    @Test fun aServerOrNetworkErrorGoesBackToReconnecting() {
        val r = LiveRefusal()
        r.opened()
        assertTrue(r.failed(404))
        assertFalse(r.failed(503))
        assertFalse(r.shown)
        r.playing()
        assertFalse(r.failed(404)) // the run started again
    }

    @Test fun playingClearsItAtOnceAndAnotherChannelStartsAfresh() {
        val r = LiveRefusal()
        r.opened()
        r.failed(410)
        r.playing()
        assertNull(r.message())
        r.opened()
        assertTrue(r.failed(401))
    }

    @Test fun oneQuickTryThenSlowly() {
        val r = LiveRefusal()
        r.opened()
        val waits = (1..6).map { r.failed(404); r.waitMs() }
        assertEquals(listOf(1_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L), waits)
    }
}
