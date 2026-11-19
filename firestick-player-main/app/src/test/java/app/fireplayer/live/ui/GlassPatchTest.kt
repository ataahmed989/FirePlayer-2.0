package app.fireplayer.live.ui

import app.fireplayer.ui.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's stamped glass (programme cells, logo tiles): how a patch is cut and what each look is made of. */
class GlassPatchTest {

    @Test fun anEndHoldsItsWholeCornerAndAPixelOfStraightEdge() {
        // A cell's 8 dp corner at 2 px per dp.
        assertEquals(17, PatchMath.cap(16f))
        // A fraction of a pixel of corner still needs its pixel.
        assertEquals(14, PatchMath.cap(12.3f))
        assertEquals(1, PatchMath.cap(0f))
        // The drawing: both ends and the few pixels of middle that are stretched.
        assertEquals(17 + PatchMath.MID + 17, PatchMath.width(17))
    }

    @Test fun aCornerIsNeverMoreThanHalfThePatchsHeight() {
        assertEquals(16f, PatchMath.radius(16f, 64), 0f)
        assertEquals(10f, PatchMath.radius(16f, 20), 0f)
        assertEquals(0f, PatchMath.radius(-3f, 20), 0f)
    }

    @Test fun aShapeNarrowerThanItsTwoEndsIsDrawnPlain() {
        assertTrue(PatchMath.fits(34, 17))
        assertTrue(PatchMath.fits(600, 17))
        assertFalse(PatchMath.fits(33, 17))
    }

    @Test fun edgesSitOnWholePixels() {
        assertEquals(100, PatchMath.snap(100.4f))
        assertEquals(101, PatchMath.snap(100.5f))
        assertEquals(-1, PatchMath.snap(-1.2f))
        // Why a patch snaps its top and its height, not its top and its bottom: both edges snapped apart would make a
        // 57.6 px cell 57 px tall at some places and 58 at others while the rows scroll.
        assertEquals(57, PatchMath.snap(10.6f + 57.6f) - PatchMath.snap(10.6f))
        assertEquals(58, PatchMath.snap(10.2f + 57.6f) - PatchMath.snap(10.2f))
        assertEquals(58, PatchMath.snap(57.6f))
    }

    @Test fun cellsAreTheSharedRowGlassWithTheSharedHairline() {
        assertEquals(GlassStyle.ROW_FILL, PatchLook.CELL.fill)
        assertEquals(1, PatchLook.CELL.coats)
        assertTrue(PatchLook.CELL.edge)
        // Gentle: the row's highlight, no sheen over a cell.
        assertEquals(GlassStyle.ROW_HIGHLIGHT, PatchLook.CELL.highlight)
        assertFalse(PatchLook.CELL.sheen)
        // The cell the focus rests on while the column has it: the same glass, a second coat.
        assertEquals(GlassStyle.ROW_FILL, PatchLook.CELL_SELECTED.fill)
        assertEquals(2, PatchLook.CELL_SELECTED.coats)
        // A row still loading: the fill alone.
        assertFalse(PatchLook.CELL_EMPTY.edge)
    }

    @Test fun cellsStaySeeThrough() {
        // Subtle transparency: even the selected cell's two coats leave most of what's behind showing.
        val coat = (GlassStyle.ROW_FILL ushr 24) / 255f
        val two = 1f - (1f - coat) * (1f - coat)
        assertTrue(coat < 0.10f)
        assertTrue(two < 0.20f)
        assertTrue(two > coat)
    }

    @Test fun logoTilesAreTheButtonsGlassAndItsLightFrost() {
        assertEquals(GlassStyle.CONTROL_TINT, PatchLook.TILE.fill)
        assertEquals(LogoPlate.GLASS_LIGHT, PatchLook.TILE_LIGHT.fill)
        // The same outline on both, so a column of them reads as one kind of tile.
        for (look in listOf(PatchLook.TILE, PatchLook.TILE_LIGHT)) {
            assertTrue(look.edge)
            assertTrue(look.sheen)
            assertEquals(GlassStyle.HIGHLIGHT, look.highlight)
        }
    }
}
