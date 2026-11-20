package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        r.follow(0L)
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

    // ---- Focus on the banner's buttons (tuning round 1: it no longer stops there) ----------------------------------

    @Test
    fun underTheButtonsItMovesOnAfterAFullWaitWithNoKeyAndKeepsGoing() {
        val r = running(5)
        r.advanceIfDue(8_000L)
        // Focus comes to Watch now a second later: the full wait starts again from then.
        r.focusButtons(9_000L)
        assertTrue(r.onButtons)
        assertTrue(r.running)
        assertEquals(8_000L, r.delayFrom(9_000L))
        assertFalse(r.advanceIfDue(16_999L))
        assertTrue(r.advanceIfDue(17_000L))
        assertEquals(2, r.index)
        // And on, every eight seconds, for as long as nothing is pressed.
        assertFalse(r.advanceIfDue(24_999L))
        assertTrue(r.advanceIfDue(25_000L))
        assertTrue(r.advanceIfDue(33_000L))
        assertEquals(4, r.index)
        assertTrue(r.onButtons)
    }

    @Test
    fun anyKeyOnTheButtonsStartsTheWaitAgain() {
        val r = running(5)
        r.focusButtons(0L)
        // Left, Right, OK, anything: seven seconds in, the wait is whole again.
        r.key(7_000L)
        assertEquals(8_000L, r.delayFrom(7_000L))
        assertFalse(r.advanceIfDue(8_000L))
        r.key(14_999L)
        assertFalse(r.advanceIfDue(15_000L))
        assertEquals(0, r.index)
        // Left alone for the full time: it moves on.
        assertTrue(r.advanceIfDue(22_999L))
        assertEquals(1, r.index)
    }

    @Test
    fun aKeyAnywhereElseDoesntTouchTheWait() {
        val r = running(5)
        // Focus in a row under the banner: the rows' keys aren't the banner's.
        r.key(5_000L)
        assertEquals(3_000L, r.delayFrom(5_000L))
        assertTrue(r.advanceIfDue(8_000L))
    }

    @Test
    fun leavingTheButtonsStartsAFullWaitOnce() {
        val r = running(5)
        r.focusButtons(1_000L)
        r.leaveButtons(5_000L)
        assertFalse(r.onButtons)
        assertEquals(8_000L, r.delayFrom(5_000L))
        // Not on them: nothing starts again.
        r.leaveButtons(6_000L)
        assertEquals(7_000L, r.delayFrom(6_000L))
    }

    @Test
    fun underTheButtonsItLeavesAContinueCardsTitleForTheFeed() {
        val r = running(5)
        r.advanceIfDue(8_000L)
        // Focus went down into the Continue row (the banner follows its card), then up to the buttons.
        r.follow(9_000L)
        assertFalse(r.running)
        r.focusButtons(10_000L)
        assertTrue(r.running)
        assertEquals(8_000L, r.delayFrom(10_000L))
        // What it turns to: the feed's title it left for the card.
        assertEquals(1, r.nextIndex())
        assertFalse(r.advanceIfDue(17_999L))
        assertTrue(r.following)
        assertTrue(r.advanceIfDue(18_000L))
        assertFalse(r.following)
        assertEquals(1, r.index)
        // From there round the feed as ever.
        assertEquals(2, r.nextIndex())
        assertTrue(r.advanceIfDue(26_000L))
        assertEquals(2, r.index)
    }

    @Test
    fun aCardThatIsTheFeedsOwnTitleIsLeftForTheOneAfter() {
        val r = running(3)
        r.advanceIfDue(8_000L)
        r.advanceIfDue(16_000L)
        assertEquals(2, r.index)
        r.follow(17_000L)
        r.focusButtons(17_000L)
        // The card followed is the feed's title 2 itself: turning to it would change nothing, so it's the next (0).
        assertEquals(0, r.nextIndex(followedIsCurrent = true))
        assertTrue(r.advanceIfDue(25_000L, followedIsCurrent = true))
        assertEquals(0, r.index)
        assertFalse(r.following)
    }

    @Test
    fun backDownIntoTheContinueRowItFollowsAndHoldsStill() {
        val r = running(5)
        r.follow(0L)
        r.focusButtons(1_000L)
        // Down: the row's card takes focus (followed again) before the buttons report they lost it.
        r.follow(4_000L)
        r.leaveButtons(4_000L)
        assertFalse(r.running)
        assertEquals(-1L, r.delayFrom(4_000L))
        assertFalse(r.advanceIfDue(100_000L))
        assertTrue(r.following)
    }

    @Test
    fun awayFromTheButtonsAndTheCardItGoesBackToTheFeedWithAFullWait() {
        val r = running(5)
        r.follow(0L)
        r.focusButtons(1_000L)
        // Right into Recent channels: the buttons are left, then the page lets the card go.
        r.leaveButtons(3_000L)
        assertFalse(r.running)
        r.unfollow(3_000L)
        assertTrue(r.running)
        assertEquals(8_000L, r.delayFrom(3_000L))
        assertEquals(0, r.index)
    }

    @Test
    fun underTheButtonsItStillHoldsWhilePaused() {
        val r = running(5)
        r.focusButtons(0L)
        // Details opened the title page over the banner.
        r.pause()
        assertFalse(r.advanceIfDue(100_000L))
        assertEquals(-1L, r.delayFrom(100_000L))
        // Back on the button: a full wait from then.
        r.resume(100_000L)
        assertEquals(8_000L, r.delayFrom(100_000L))
        assertTrue(r.advanceIfDue(108_000L))
    }

    @Test
    fun oneTitleOrNoneDoesntMove() {
        val one = running(1)
        assertFalse(one.running)
        assertEquals(-1L, one.delayFrom(0L))
        assertEquals(-1, one.nextIndex())
        val none = running(0)
        assertFalse(none.advanceIfDue(1_000_000L))
        // Nor under the buttons, whether a card is followed or not: there is nothing to move on to.
        one.follow(0L)
        one.focusButtons(0L)
        assertFalse(one.running)
        assertFalse(one.advanceIfDue(1_000_000L))
        assertTrue(one.following)
        none.focusButtons(0L)
        assertFalse(none.advanceIfDue(2_000_000L))
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

    // ---- OK on the buttons: which title it acts on --------------------------------------------------------------

    private enum class B { WATCH, DETAILS }

    @Test
    fun okActsOnTheTitleWhoseWordsAreShowing() {
        val p = HeaderPress<B, String>()
        p.down(B.WATCH, "A", repeat = 0)
        assertEquals("A", p.up(B.WATCH, canceled = false))
        // One press, one act.
        assertNull(p.up(B.WATCH, canceled = false))
    }

    @Test
    fun theWordsChangeInTheMiddleOfTheFadeAndAPressBelongsToTheTitleItWentDownOn() {
        val p = HeaderPress<B, String>()
        // The banner is turning from A to B. OK goes down while A's words are still fading out; they change (nothing
        // showing) and B's fade in before the release: the press was on A.
        p.down(B.DETAILS, "A", repeat = 0)
        assertEquals("A", p.up(B.DETAILS, canceled = false))
        // A press that goes down after the change, B's words fading in: B.
        p.down(B.DETAILS, "B", repeat = 0)
        assertEquals("B", p.up(B.DETAILS, canceled = false))
    }

    @Test
    fun aHeldOkKeepsTheTitleItWentDownOn() {
        val p = HeaderPress<B, String>()
        p.down(B.WATCH, "A", repeat = 0)
        // Its repeats arrive with whatever shows then: they change nothing.
        p.down(B.WATCH, "B", repeat = 1)
        p.down(B.WATCH, "B", repeat = 7)
        assertEquals("A", p.up(B.WATCH, canceled = false))
    }

    @Test
    fun aPressThatIsntWholeActsOnNothing() {
        val p = HeaderPress<B, String>()
        // Released on the other button (focus moved between down and up).
        p.down(B.WATCH, "A", repeat = 0)
        assertNull(p.up(B.DETAILS, canceled = false))
        // Cancelled by the system.
        p.down(B.WATCH, "A", repeat = 0)
        assertNull(p.up(B.WATCH, canceled = true))
        // Another key between down and up, or focus gone from the buttons.
        p.down(B.WATCH, "A", repeat = 0)
        p.cancel()
        assertNull(p.up(B.WATCH, canceled = false))
        // A release whose press began somewhere else (the OK that brought focus here).
        assertNull(p.up(B.WATCH, canceled = false))
        // No title showing (the banner emptied).
        p.down(B.WATCH, null, repeat = 0)
        assertNull(p.up(B.WATCH, canceled = false))
    }

    // ---- The title box: the logo, or the name ------------------------------------------------------------------------

    @Test
    fun aLogoInMemoryShowsAtOnceAndATitleWithoutOneShowsItsName() {
        assertEquals(HeaderTitleMode.LOGO, HeaderTitleMode.of(hasLogo = true, ready = true, failed = false))
        assertEquals(HeaderTitleMode.NAME, HeaderTitleMode.of(hasLogo = false, ready = false, failed = false))
        // One whose address gave no picture lately isn't waited for.
        assertEquals(HeaderTitleMode.NAME, HeaderTitleMode.of(hasLogo = true, ready = false, failed = true))
    }

    @Test
    fun aLogoOnItsWayIsWaitedForWithTheBoxEmptyNeverTheNameFirst() {
        val waiting = HeaderTitleMode.of(hasLogo = true, ready = false, failed = false)
        assertEquals(HeaderTitleMode.WAITING, waiting)
        // It comes in time: the logo.
        assertEquals(HeaderTitleMode.LOGO, waiting.arrived())
        // It doesn't: the name, and a logo arriving after that never replaces it.
        val name = waiting.waited()
        assertEquals(HeaderTitleMode.NAME, name)
        assertEquals(HeaderTitleMode.NAME, name.arrived())
        // A logo showing isn't swapped for the name by a late timer; an empty banner stays empty.
        assertEquals(HeaderTitleMode.LOGO, HeaderTitleMode.LOGO.waited())
        assertEquals(HeaderTitleMode.NONE, HeaderTitleMode.NONE.arrived())
        assertEquals(HeaderTitleMode.NONE, HeaderTitleMode.NONE.waited())
    }

    // ---- The name's lines -----------------------------------------------------------------------------------------------

    /** Ten px a character. */
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun aShortNameIsOneLine() {
        assertEquals(listOf("Dune"), HeaderName.lines("Dune", 300f, 1, measure))
        assertEquals(listOf("Dune"), HeaderName.lines("Dune", 300f, 2, measure))
        assertEquals(emptyList<String>(), HeaderName.lines("", 300f, 2, measure))
    }

    @Test
    fun aLongNameBreaksBetweenWordsAndIsCutShortOnTheLastLine() {
        // 20 characters a line.
        assertEquals(
            listOf("The Lord of the", "Rings: The Rings of…"),
            HeaderName.lines("The Lord of the Rings: The Rings of Power", 200f, 2, measure),
        )
        // Two lines are enough: nothing is cut.
        assertEquals(listOf("Avatar: The Last", "Airbender"), HeaderName.lines("Avatar: The Last Airbender", 200f, 2, measure))
        // One line only: cut there.
        assertEquals(listOf("Avatar: The Last Ai…"), HeaderName.lines("Avatar: The Last Airbender", 200f, 1, measure))
        // No line is ever wider than the box.
        for (line in HeaderName.lines("Supercalifragilisticexpialidocious and the rest of it", 200f, 2, measure)) {
            assertTrue(measure(line) <= 200f)
        }
    }
}
