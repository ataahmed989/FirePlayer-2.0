package app.fireplayer.live.ui

import app.fireplayer.live.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCellsTest {

    private val min = 60_000L
    private val base = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, GuideCells.SLOT_MS)
    private fun p(startMin: Int, stopMin: Int, title: String) = Programme(base + startMin * min, base + stopMin * min, title, null, null)

    @Test fun cellsTileTheRowWithGaps() {
        val cells = GuideCells.cells(listOf(p(30, 60, "B"), p(-20, 10, "A"), p(90, 200, "C")), base, base + 120 * min)
        assertEquals(listOf("A", null, "B", null, "C"), cells.map { it.programme?.title })
        // No holes, no overlaps, exactly the requested range.
        assertEquals(base, cells.first().start)
        assertEquals(base + 120 * min, cells.last().stop)
        for (i in 1 until cells.size) assertEquals(cells[i - 1].stop, cells[i].start)
        // The clipped first cell still knows the programme's true start.
        assertEquals(base - 20 * min, cells[0].programme!!.start)
    }

    @Test fun overlappingProgrammesAreShortened() {
        val cells = GuideCells.cells(listOf(p(0, 40, "A"), p(30, 60, "B")), base, base + 60 * min)
        assertEquals(listOf("A", "B"), cells.map { it.programme?.title })
        assertEquals(base + 40 * min, cells[1].start)
    }

    @Test fun emptyRowIsOneNoInformationCell() {
        val cells = GuideCells.cells(emptyList(), base, base + 60 * min)
        assertEquals(1, cells.size)
        assertNull(cells[0].programme)
        assertTrue(GuideCells.cells(emptyList(), base, base).isEmpty())
    }

    @Test fun atFindsTheCellOrTheNearest() {
        val cells = GuideCells.cells(listOf(p(0, 30, "A"), p(30, 60, "B")), base, base + 60 * min)
        assertEquals(1, GuideCells.at(cells, base + 45 * min))
        assertEquals(0, GuideCells.at(cells, base - 5 * min))
        assertEquals(1, GuideCells.at(cells, base + 90 * min))
        assertEquals(-1, GuideCells.at(emptyList(), base))
    }

    @Test fun cursorStaysOnNowForTheProgrammeOnAir() {
        val cells = GuideCells.cells(listOf(p(0, 60, "On air"), p(60, 90, "Next")), base, base + 90 * min)
        val now = base + 12 * min
        assertEquals(now, GuideCells.cursorFor(cells[0], now))
        assertEquals(base + 60 * min, GuideCells.cursorFor(cells[1], now))
    }

    @Test fun windowMovesInHalfHourStepsAndNeverBeforeNow() {
        val window = 120 * min
        val earliest = base
        val later = GuideCells.cells(listOf(p(150, 180, "Later")), base, base + 300 * min).first { it.programme != null }
        val w = GuideCells.windowFor(later, base, window, earliest)
        assertTrue(later.start in w until w + window)
        assertEquals(0L, Math.floorMod(w - base, GuideCells.SLOT_MS))
        // Coming back left, the window returns, but not before the slot holding now.
        val first = GuideCells.cells(listOf(p(0, 30, "Now")), base, base + 30 * min)[0]
        assertEquals(base, GuideCells.windowFor(first, w, window, earliest))
        // A cell already visible doesn't move the window.
        val visible = GuideCells.cells(listOf(p(30, 60, "Soon")), base + 30 * min, base + 60 * min)[0]
        assertEquals(base, GuideCells.windowFor(visible, base, window, earliest))
    }

    @Test fun slotStart() {
        assertEquals(base, GuideCells.slotStart(base + 29 * min))
        assertEquals(base + 30 * min, GuideCells.slotStart(base + 30 * min))
    }
}
