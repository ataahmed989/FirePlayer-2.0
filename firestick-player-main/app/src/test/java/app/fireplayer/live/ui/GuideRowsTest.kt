package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's rows: how many whole ones show, where the rows scroll as focus moves, and long moves (going round). */
class GuideRowsTest {

    // At the Fire TV's density (2): 26 dp header, 29 dp rows.
    private val header = 52f
    private val row = 58f

    @Test fun theGuideShowsNineWholeRowsAndTheTenthCutAtTheBottom() {
        // The guide from under the miniature to the screen's bottom edge: 540 - 14 - 217 = 309 dp.
        val height = 618f
        val whole = GuideCells.wholeRows(height, header, row)
        assertEquals(9, whole)
        // The space under the ninth row shows most of a tenth one, cut by the screen's edge.
        val tenth = height - header - whole * row
        assertTrue(tenth > row / 2 && tenth < row)
    }

    @Test fun aShortGuideStillHasARow() {
        assertEquals(1, GuideCells.wholeRows(60f, header, row))
        assertEquals(1, GuideCells.wholeRows(0f, header, row))
    }

    @Test fun movingDownKeepsTheFocusOnWholeRowsWithOneRowOfRoom() {
        val visible = 9
        val count = 40
        var top = 0
        for (r in 0 until count) {
            top = GuideCells.topFor(r, top, visible, count)
            // Never the row cut at the bottom (index top + visible), always a whole one.
            assertTrue("row $r top $top", r in top until top + visible)
            // A row of room under it, except at the end of the list.
            if (top < count - visible) assertTrue("row $r top $top", r <= top + visible - 2)
        }
        assertEquals(count - visible, top)
    }

    @Test fun movingUpKeepsTheFocusOnWholeRowsWithOneRowOfRoom() {
        val visible = 9
        val count = 40
        var top = count - visible
        for (r in count - 1 downTo 0) {
            top = GuideCells.topFor(r, top, visible, count)
            assertTrue("row $r top $top", r in top until top + visible)
            if (top > 0) assertTrue("row $r top $top", r >= top + 1)
        }
        assertEquals(0, top)
    }

    @Test fun goingRoundShowsTheOtherEnd() {
        // Down from the last channel to the first, and Up from the first to the last.
        assertEquals(0, GuideCells.topFor(0, 31, 9, 40))
        assertEquals(31, GuideCells.topFor(39, 0, 9, 40))
        // A list shorter than the screen never scrolls.
        assertEquals(0, GuideCells.topFor(5, 0, 9, 6))
        assertEquals(0, GuideCells.topFor(0, 0, 9, 6))
    }

    @Test fun tooFewRowsForRoomStillShowTheFocus() {
        for (visible in 1..2) {
            var top = 0
            for (r in 0 until 10) {
                top = GuideCells.topFor(r, top, visible, 10)
                assertTrue("visible $visible row $r top $top", r in top until top + visible)
            }
        }
    }

    @Test fun aLongMoveJumpsAndGlidesTheLastFewRows() {
        val screen = 9 * row
        val glide = 3 * row
        // Round from the last of 300 channels to the first: the rows start 3 rows short of the top.
        assertEquals(glide, GuideCells.scrollStart(291 * row, 0f, screen, glide), 0.01f)
        // And from the first to the last.
        assertEquals(291 * row - glide, GuideCells.scrollStart(0f, 291 * row, screen, glide), 0.01f)
        // A move within a screen (a row, a page) scrolls from where the rows are.
        assertEquals(10 * row, GuideCells.scrollStart(10 * row, 11 * row, screen, glide), 0.01f)
        assertEquals(10 * row, GuideCells.scrollStart(10 * row, 1 * row, screen, glide), 0.01f)
    }

    @Test fun theHighlightKeepsItsPlaceOnScreenThroughTheJump() {
        val g = FocusGlide()
        // Drawn on the last row of a long list (content y), the rows scrolled to 291 rows.
        val scroll = 291 * row
        g.begin(100f, header + 299 * row, 300f, header + 300 * row)
        val onScreen = header + 299 * row - scroll
        // The rows jump to 3 rows from the top: the glide's start moves with them.
        val jumped = 3 * row
        g.shiftY(jumped - scroll)
        g.fraction = 0f
        g.update(100f, header, 300f, header + row)
        assertEquals(onScreen, g.top - jumped, 0.01f)
        // At the end it's on the first row.
        g.fraction = 1f
        g.update(100f, header, 300f, header + row)
        assertEquals(header, g.top, 0.01f)
    }
}
