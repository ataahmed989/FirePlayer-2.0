package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldPopupTest {

    // ---- OK pressed or held (PressHold) -------------------------------------------------------------------------

    @Test fun aShortPressPicksOnRelease() {
        val p = PressHold(500)
        assertFalse(p.down(0, false, 0))
        assertTrue(p.up(false))
        // Once: a second release with no press is nothing.
        assertFalse(p.up(false))
    }

    @Test fun holdingOpensOnceAndTheReleaseDoesNothing() {
        val p = PressHold(500)
        assertFalse(p.down(0, false, 0))
        // The first repeat comes flagged as a long press.
        assertTrue(p.down(1, true, 500))
        // Later repeats while still held: nothing more.
        assertFalse(p.down(2, false, 550))
        assertFalse(p.down(3, false, 600))
        assertFalse(p.up(false))
    }

    @Test fun aRepeatPastTheLongPressTimeHoldsEvenUnflagged() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.down(1, false, 120))
        assertTrue(p.down(2, false, 520))
        assertFalse(p.up(false))
    }

    @Test fun anEarlyRepeatStillEndsAsAShortPress() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.down(1, false, 200))
        assertTrue(p.up(false))
    }

    @Test fun aPressThatStartedElsewhereIsNotOurs() {
        val p = PressHold(500)
        // Focus came here while OK was held: its repeats and release do nothing.
        assertFalse(p.down(4, true, 700))
        assertFalse(p.down(5, false, 750))
        assertFalse(p.up(false))
    }

    @Test fun focusLeavingOrAnotherKeyCancelsThePick() {
        val p = PressHold(500)
        p.down(0, false, 0)
        p.cancel()
        assertFalse(p.down(1, true, 500))
        assertFalse(p.up(false))
    }

    @Test fun aCanceledReleaseDoesNotPick() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.up(true))
    }

    @Test fun aNewPressAfterAHoldStartsAfresh() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertTrue(p.down(1, true, 500))
        // The release went to the small window; the next press here is a normal one.
        assertFalse(p.down(0, false, 0))
        assertTrue(p.up(false))
    }

    // ---- Where the small window goes (HoldPlace) --------------------------------------------------------------------

    @Test fun itSitsBesideTheRowCentredOnIt() {
        assertEquals(328f, HoldPlace.x(anchorRight = 320f, width = 200f, gap = 8f, left = 0f, right = 960f), 0f)
        assertEquals(250f, HoldPlace.y(anchorCenterY = 300f, height = 100f, top = 0f, bottom = 540f), 0f)
    }

    @Test fun itMovesLeftWhenThereIsNoRoomBesideTheRow() {
        assertEquals(560f, HoldPlace.x(anchorRight = 700f, width = 400f, gap = 8f, left = 0f, right = 960f), 0f)
        // Never past the left edge, even when wider than the area.
        assertEquals(12f, HoldPlace.x(anchorRight = 700f, width = 2000f, gap = 8f, left = 12f, right = 960f), 0f)
    }

    @Test fun itStaysInsideTheAreaTopAndBottom() {
        // A row at the top of the guide: the window starts at the guide's top.
        assertEquals(230f, HoldPlace.y(anchorCenterY = 240f, height = 100f, top = 230f, bottom = 530f), 0f)
        // A row at the bottom: it ends at the guide's bottom.
        assertEquals(430f, HoldPlace.y(anchorCenterY = 520f, height = 100f, top = 230f, bottom = 530f), 0f)
        // Taller than the area: from its top.
        assertEquals(230f, HoldPlace.y(anchorCenterY = 400f, height = 400f, top = 230f, bottom = 530f), 0f)
    }

    // ---- Move to top and Hide group (GroupEdits) -----------------------------------------------------------------

    private fun group(id: String) = ChannelGroup("p", id, id.uppercase(), 0)

    @Test fun moveToTopPutsTheGroupFirstAndKeepsTheOthersInOrder() {
        val groups = listOf(group("a"), group("b"), group("c"), group("d"))
        assertEquals(listOf("c", "a", "b", "d"), GroupEdits.toTop(groups, "c") { it.id }.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), GroupEdits.toTop(listOf("a", "b", "c", "d"), "d"))
    }

    @Test fun moveToTopLeavesTheListAsItIsWhenFirstOrAbsent() {
        val ids = listOf("a", "b", "c")
        assertSame(ids, GroupEdits.toTop(ids, "a"))
        assertSame(ids, GroupEdits.toTop(ids, "x"))
        assertTrue(GroupEdits.toTop(emptyList(), "a").isEmpty())
    }

    @Test fun afterHidingTheNextGroupTakesItsPlace() {
        assertEquals("c", GroupEdits.afterHide(listOf("a", "b", "c"), "b"))
        assertEquals("b", GroupEdits.afterHide(listOf("a", "b", "c"), "a"))
    }

    @Test fun afterHidingTheLastGroupTheOneAboveIsSelected() {
        assertEquals("b", GroupEdits.afterHide(listOf("a", "b", "c"), "c"))
    }

    @Test fun afterHidingTheOnlyGroupThereIsNoneLeft() {
        // The column then selects the playlist's All channels, the row above.
        assertNull(GroupEdits.afterHide(listOf("a"), "a"))
        assertNull(GroupEdits.afterHide(listOf("a", "b"), "x"))
    }
}
