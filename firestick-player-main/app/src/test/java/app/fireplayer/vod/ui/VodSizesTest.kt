package app.fireplayer.vod.ui

import app.fireplayer.vod.RowStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

class VodSizesTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.05f) = assertEquals(expected, actual, tolerance)

    @Test
    fun postersAreSixAndAHalfAcrossAtThreeByFour() {
        assertEquals(121f, VodSizes.POSTER_W)
        assertEquals(162f, VodSizes.POSTER_H)
        // 6.5 of them (with their gaps) fill the content's width from x 94 to the screen's edge.
        near(VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X, 6.5f * (VodSizes.across(6.5f) + VodSizes.GAP) - VodSizes.GAP)
        // Grown 16%: 141 x 188 dp (design-brief §6.3, whole dp rounded up, as the pictures are decoded).
        assertEquals(141, ceil(VodSizes.across(6.5f) * 1.16f).toInt())
        assertEquals(188, ceil(VodSizes.across(6.5f) * 4f / 3f * 1.16f).toInt())
    }

    @Test
    fun wideCardsAreFourAndAHalfAcrossAtSixteenByNine() {
        assertEquals(182f, VodSizes.WIDE_W)
        assertEquals(102f, VodSizes.WIDE_H)
        assertEquals(211, ceil(VodSizes.across(4.5f) * 1.16f).toInt())
        assertEquals(119, ceil(VodSizes.across(4.5f) * 9f / 16f * 1.16f).toInt())
    }

    @Test
    fun contentStartsAt94OnScreenAnd36InThePage() {
        assertEquals(58f, VodSizes.RAIL)
        assertEquals(36f, VodSizes.PAGE_X)
    }

    @Test
    fun homeHeaderAndWindowFillTheWidthWithTheRightMargin() {
        assertEquals(496f, VodSizes.homeHeaderW())
        assertEquals(VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X, VodSizes.homeHeaderW() + 16f + 330f + 24f)
        // The window's right edge sits 24 dp from the screen's (page x + rail = screen x).
        assertEquals(936f, VodSizes.windowX() + VodSizes.WINDOW_W + VodSizes.RAIL)
        assertEquals(842f, VodSizes.tabHeaderW())
    }

    @Test
    fun pitchesAndFollowerShifts() {
        near(135f, VodSizes.pitch(RowStyle.POSTER))
        near(196f, VodSizes.pitch(RowStyle.WIDE))
        near(195.32f, VodSizes.pitch(RowStyle.RANKED), 0.01f)
        near(19.36f, VodSizes.extra(RowStyle.POSTER), 0.001f)
        near(29.12f, VodSizes.extra(RowStyle.WIDE), 0.001f)
    }

    @Test
    fun rankSlotAndTextAreFractionsOfThePosterHeight() {
        near(60.32f, VodSizes.slot(RowStyle.RANKED), 0.01f)
        assertEquals(0f, VodSizes.slot(RowStyle.POSTER))
        near(111.89f, VodSizes.POSTER_H * VodSizes.RANK_TEXT, 0.01f)
        near(0.97f, VodSizes.POSTER_H * VodSizes.RANK_BASELINE, 0.01f)
        // Glass Home: slot 124 of a 333 poster; text 230.
        near(124f / 333f, VodSizes.RANK_SLOT, 0.0001f)
    }

    @Test
    fun bandHeights() {
        near(231.92f, VodSizes.bandH(RowStyle.POSTER), 0.01f)
        near(231.92f, VodSizes.bandH(RowStyle.RANKED), 0.01f)
        near(162.32f, VodSizes.bandH(RowStyle.WIDE), 0.01f)
        near(81.44f, VodSizes.bandH(RowStyle.GENRES), 0.01f)
        // The chosen card's growth is centred in the strip.
        near(12.96f, VodSizes.cardTop(RowStyle.POSTER), 0.001f)
        near(8.16f, VodSizes.cardTop(RowStyle.WIDE), 0.001f)
    }

    @Test
    fun theSizingPagesGeometryHolds() {
        // Header shown: the first row's title at screen y 255, its posters from about 294 to 456.
        val firstRowScreen = VodSizes.VIEWPORT_TOP + VodSizes.firstRowTop()
        assertEquals(255f, firstRowScreen)
        near(293.96f, firstRowScreen + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER), 0.01f)
        // Scrolled down: the chosen row's title just under the name line, two rows of posters and the third row's
        // title in view.
        val second = VodSizes.VIEWPORT_TOP + VodSizes.bandH(RowStyle.POSTER)
        val third = second + VodSizes.bandH(RowStyle.POSTER)
        assertEquals(true, third < VodSizes.SCREEN_H)
        assertEquals(true, third + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER) >= VodSizes.SCREEN_H - 20f)
        assertEquals(496f, VodSizes.VIEWPORT_H)
    }

    @Test
    fun headerParts() {
        assertEquals(211f, VodSizes.firstRowTop())
        assertEquals(31.2f, VodSizes.headerTextTop(), 0.001f)
        // The backdrop: at most 2.6 header heights wide, right-aligned.
        assertEquals(496f, VodSizes.backdropW(VodSizes.homeHeaderW()))
        near(507f, VodSizes.backdropW(VodSizes.tabHeaderW()), 0.001f)
        near(470f, VodSizes.storyW(VodSizes.tabHeaderW()))
        near(496f - 26f - 0.22f * 496f, VodSizes.storyW(VodSizes.homeHeaderW()))
    }

    @Test
    fun gridIsSixAcross() {
        assertEquals(796f, VodSizes.gridW())
        assertEquals(192f, VodSizes.gridPitchY())
        // 2.5 grid rows fill the viewport under the name line.
        near(VodSizes.VIEWPORT_H, VodSizes.GRID_TOP + 2.5f * VodSizes.gridPitchY())
        // The grown poster (about its middle) and its ring stay inside the gaps.
        assertEquals(true, VodSizes.POSTER_W * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GAP)
        assertEquals(true, VodSizes.POSTER_H * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GRID_ROW_GAP)
    }
}
