package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderRotationTest {

    private fun running(count: Int, now: Long = 0L) = HeaderRotation().apply {
        setFeed(count, now)
        resume(now)
    }

    @Test
    fun movesOnEveryEightSeconds() {
        val r = running(10)
        assertEquals(8_000L, r.delayFrom(0L))
        assertFalse(r.advanceIfDue(7_999L))
        assertEquals(0, r.index)
        assertTrue(r.advanceIfDue(8_000L))
        assertEquals(1, r.index)
        assertEquals(8_000L, r.delayFrom(8_000L))
        assertTrue(r.advanceIfDue(16_000L))
        assertEquals(2, r.index)
    }

    @Test
    fun wrapsAtTheEndOfTheFeed() {
        val r = running(3)
        var now = 0L
        repeat(3) {
            now += 8_000L
            r.advanceIfDue(now)
        }
        assertEquals(0, r.index)
        assertEquals(1, r.nextIndex())
    }

    @Test
    fun followingTheContinueRowHoldsItThenTheWaitStartsAgain() {
        val r = running(5)
        r.follow()
        assertFalse(r.running)
        assertEquals(-1L, r.delayFrom(1_000L))
        assertFalse(r.advanceIfDue(50_000L))
        assertEquals(0, r.index)
        r.unfollow(50_000L)
        assertEquals(8_000L, r.delayFrom(50_000L))
        assertTrue(r.advanceIfDue(58_000L))
        assertEquals(1, r.index)
    }

    @Test
    fun pausedWhileHiddenAndResumedWithAFullWait() {
        val r = running(5)
        r.advanceIfDue(8_000L)
        r.pause()
        assertTrue(r.paused)
        assertFalse(r.advanceIfDue(100_000L))
        assertEquals(1, r.index)
        r.resume(100_000L)
        assertEquals(8_000L, r.delayFrom(100_000L))
        assertFalse(r.advanceIfDue(107_000L))
        assertTrue(r.advanceIfDue(108_000L))
        assertEquals(2, r.index)
    }

    @Test
    fun heldWhileABannerButtonHasFocusThenAFullWait() {
        val r = running(5)
        r.advanceIfDue(8_000L)
        r.hold()
        assertTrue(r.held)
        assertFalse(r.running)
        assertEquals(-1L, r.delayFrom(9_000L))
        assertFalse(r.advanceIfDue(100_000L))
        assertEquals(1, r.index)
        r.unhold(100_000L)
        assertFalse(r.held)
        assertEquals(8_000L, r.delayFrom(100_000L))
        assertTrue(r.advanceIfDue(108_000L))
        assertEquals(2, r.index)
        // Not held: nothing starts again.
        r.unhold(109_000L)
        assertEquals(7_000L, r.delayFrom(109_000L))
    }

    @Test
    fun heldOnAFollowedCardStaysStillUntilBothEnd() {
        val r = running(5)
        r.follow()
        r.hold()
        // Focus went up from the Continue row to the buttons, then away: the card is let go, then the hold.
        r.unfollow(20_000L)
        assertFalse(r.running)
        assertEquals(-1L, r.delayFrom(20_000L))
        r.unhold(30_000L)
        assertTrue(r.running)
        assertEquals(8_000L, r.delayFrom(30_000L))
        assertEquals(0, r.index)
    }

    @Test
    fun oneTitleOrNoneDoesntMove() {
        val one = running(1)
        assertFalse(one.running)
        assertEquals(-1L, one.delayFrom(0L))
        assertEquals(-1, one.nextIndex())
        val none = running(0)
        assertFalse(none.advanceIfDue(1_000_000L))
    }

    @Test
    fun aNewFeedKeepsTheTitleWhenItCanAndRestartsTheWait() {
        val r = running(10)
        r.advanceIfDue(8_000L)
        r.advanceIfDue(16_000L)
        assertEquals(2, r.index)
        // Shorter feed: the index is past its end, so it starts over.
        r.setFeed(2, 17_000L)
        assertEquals(0, r.index)
        r.setFeed(10, 18_000L)
        r.jumpTo(4, 18_000L)
        assertEquals(4, r.index)
        assertEquals(8_000L, r.delayFrom(18_000L))
        // Restarted (a window was open at the turn): a full wait from then.
        r.restart(20_000L)
        assertEquals(8_000L, r.delayFrom(20_000L))
    }

    @Test
    fun startsPaused() {
        val r = HeaderRotation()
        r.setFeed(10, 0L)
        assertTrue(r.paused)
        assertFalse(r.advanceIfDue(1_000_000L))
    }
}
