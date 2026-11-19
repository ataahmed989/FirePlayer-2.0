package app.fireplayer.vod.ui

import app.fireplayer.vod.RowStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class VodSizesTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.05f) = assertEquals(expected, actual, tolerance)

    // ---- The title page's cards: the sizes of 2026-11-18, unchanged -------------------------------------------------

    @Test
    fun theTitlePagesPostersAreSixAndAHalfAcrossAtThreeByFour() {
        assertEquals(121f, VodSizes.POSTER_W)
        assertEquals(162f, VodSizes.POSTER_H)
        // 6.5 of them (with their gaps) fill the content's width from x 94 to the screen's edge.
        near(VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X, 6.5f * (VodSizes.across(6.5f) + VodSizes.GAP) - VodSizes.GAP)
        // Grown 16%: 141 x 188 dp (design-brief §6.3, whole dp rounded up, as the pictures are decoded).
        assertEquals(141, ceil(VodSizes.across(6.5f) * 1.16f).toInt())
        assertEquals(188, ceil(VodSizes.across(6.5f) * 4f / 3f * 1.16f).toInt())
    }

    @Test
    fun theTitlePagesStillsAreFourAndAHalfAcrossAtSixteenByNine() {
        assertEquals(182f, VodSizes.WIDE_W)
        assertEquals(102f, VodSizes.WIDE_H)
        assertEquals(211, ceil(VodSizes.across(4.5f) * 1.16f).toInt())
        assertEquals(119, ceil(VodSizes.across(4.5f) * 9f / 16f * 1.16f).toInt())
        // An episode card keeps its size whatever the row's.
        assertEquals(VodSizes.WIDE_W, VodSizes.cardW(RowStyle.EPISODE, large = true))
        assertEquals(VodSizes.WIDE_H, VodSizes.pictureH(RowStyle.EPISODE, large = true))
    }

    @Test
    fun theTitlePagesPitchesBandsAndFollowerShifts() {
        near(135f, VodSizes.pitch(RowStyle.POSTER))
        near(196f, VodSizes.pitch(RowStyle.WIDE))
        near(195.32f, VodSizes.pitch(RowStyle.RANKED), 0.01f)
        near(19.36f, VodSizes.extra(RowStyle.POSTER), 0.001f)
        near(29.12f, VodSizes.extra(RowStyle.WIDE), 0.001f)
        near(231.92f, VodSizes.bandH(RowStyle.POSTER), 0.01f)
        near(231.92f, VodSizes.bandH(RowStyle.RANKED), 0.01f)
        near(162.32f, VodSizes.bandH(RowStyle.WIDE), 0.01f)
        near(81.44f, VodSizes.bandH(RowStyle.GENRES), 0.01f)
        near(12.96f, VodSizes.cardTop(RowStyle.POSTER), 0.001f)
        near(8.16f, VodSizes.cardTop(RowStyle.WIDE), 0.001f)
        near(60.32f, VodSizes.slot(RowStyle.RANKED), 0.01f)
    }

    // ---- The browse pages' cards (the brief of 2026-11-19) ---------------------------------------------------------

    @Test
    fun continueWatchingIsAboutThreeAndAThirdAcross() {
        assertEquals(253f, VodSizes.BROWSE_WIDE_W)
        assertEquals(142f, VodSizes.BROWSE_WIDE_H)
        // Three whole cards and part of a fourth, with nothing chosen and with the first one grown.
        val content = VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X
        val pitch = VodSizes.pitch(RowStyle.WIDE, large = true)
        near(267f, pitch)
        val fourth = 3 * pitch
        assertTrue(fourth + 20f < content && fourth + VodSizes.BROWSE_WIDE_W > content)
        val fourthGrown = fourth + VodSizes.extra(RowStyle.WIDE, large = true)
        near(40.48f, VodSizes.extra(RowStyle.WIDE, large = true), 0.001f)
        assertTrue(fourthGrown + 20f < content)
    }

    @Test
    fun postersAreLargerByTheSameFactorAtThreeByFour() {
        assertEquals(169f, VodSizes.BROWSE_POSTER_W)
        assertEquals(226f, VodSizes.BROWSE_POSTER_H)
        // The same factor as the wide cards': 4.5 / 3.3 of the old sizes, within a dp or two.
        val factor = VodSizes.WIDE_ACROSS / VodSizes.BROWSE_WIDE_ACROSS
        near(VodSizes.POSTERS_ACROSS / factor, VodSizes.BROWSE_POSTERS_ACROSS, 0.05f)
        near(VodSizes.BROWSE_POSTER_W / VodSizes.POSTER_W, VodSizes.BROWSE_WIDE_W / VodSizes.WIDE_W, 0.02f)
        near(183f, VodSizes.pitch(RowStyle.POSTER, large = true))
        near(27.04f, VodSizes.extra(RowStyle.POSTER, large = true), 0.001f)
    }

    @Test
    fun rankedNumbersGrowWithTheirPosters() {
        near(84.16f, VodSizes.slot(RowStyle.RANKED, large = true), 0.01f)
        assertEquals(0f, VodSizes.slot(RowStyle.POSTER, large = true))
        near(267.16f, VodSizes.pitch(RowStyle.RANKED, large = true), 0.01f)
        near(156.1f, VodSizes.BROWSE_POSTER_H * VodSizes.RANK_TEXT, 0.01f)
        // Glass Home: slot 124 of a 333 poster; text 230.
        near(124f / 333f, VodSizes.RANK_SLOT, 0.0001f)
    }

    @Test
    fun browseBandHeights() {
        near(306.16f, VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true), 0.01f)
        near(306.16f, VodSizes.bandH(RowStyle.RANKED, true, VodSizes.ROW_GAP, large = true), 0.01f)
        near(208.72f, VodSizes.bandH(RowStyle.WIDE, true, VodSizes.ROW_GAP, large = true), 0.01f)
        // Chips keep their size.
        near(81.44f, VodSizes.bandH(RowStyle.GENRES, true, VodSizes.ROW_GAP, large = true), 0.01f)
        // The chosen card's growth is centred in the strip.
        near(18.08f, VodSizes.cardTop(RowStyle.POSTER, large = true), 0.001f)
        near(11.36f, VodSizes.cardTop(RowStyle.WIDE, large = true), 0.001f)
    }

    @Test
    fun picturesAreHeldForTheCardsThatShowAndOneAhead() {
        assertEquals(4, VodSizes.boundAfter(RowStyle.WIDE, large = true))
        assertEquals(5, VodSizes.boundAfter(RowStyle.POSTER, large = true))
        assertEquals(4, VodSizes.boundAfter(RowStyle.RANKED, large = true))
        assertEquals(7, VodSizes.boundAfter(RowStyle.POSTER, large = false))
        assertEquals(3, VodSizes.boundAfter(RowStyle.POSTER, large = true, most = 3))
    }

    // ---- The page ----------------------------------------------------------------------------------------------------

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
    fun theBannerIsTallerAndTheFirstRowStartsUnderIt() {
        assertEquals(216f, VodSizes.HEADER_H)
        assertEquals(232f, VodSizes.firstRowTop())
        val firstRowScreen = VodSizes.VIEWPORT_TOP + VodSizes.firstRowTop()
        assertEquals(276f, firstRowScreen)
        assertEquals(496f, VodSizes.VIEWPORT_H)
        // Continue Watching under the banner shows whole, its chosen card grown; the next row's title shows under it,
        // its posters running on below the screen (nothing shrinks to fit).
        val wide = VodSizes.rowShownH(RowStyle.WIDE, header = true, large = true)
        assertTrue(firstRowScreen + wide <= VodSizes.SCREEN_H)
        val next = firstRowScreen + VodSizes.bandH(RowStyle.WIDE, true, VodSizes.ROW_GAP, large = true)
        assertTrue(next + VodSizes.ROW_TITLE_STYLE.lineSp < VodSizes.SCREEN_H)
        assertTrue(next + VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true) > VodSizes.SCREEN_H)
    }

    @Test
    fun aFirstRowOfPostersMovesUpJustFarEnoughToShowWhole() {
        val top = VodSizes.firstRowTop()
        val posters = VodSizes.rowShownH(RowStyle.POSTER, header = true, large = true)
        near(292.16f, posters, 0.01f)
        near(28.16f, RowMath.pageTarget(0, 0, posters, VodSizes.VIEWPORT_H) { top }, 0.01f)
        // Most of the banner still shows, its words and buttons with it.
        assertTrue(VodSizes.headerTextTop() > 28.16f)
        val wide = VodSizes.rowShownH(RowStyle.WIDE, header = true, large = true)
        assertEquals(0f, RowMath.pageTarget(0, 0, wide, VodSizes.VIEWPORT_H) { top })
    }

    @Test
    fun scrolledDownAChosenRowAndMostOfTheNextShow() {
        val second = VodSizes.VIEWPORT_TOP + VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true)
        assertTrue(second < VodSizes.SCREEN_H)
        // The next row's posters start on screen and run on below it.
        val posters = second + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER, large = true)
        assertTrue(posters + 100f < VodSizes.SCREEN_H && posters + VodSizes.BROWSE_POSTER_H > VodSizes.SCREEN_H)
    }

    @Test
    fun headerParts() {
        // The words stand on the buttons, the buttons above the dots.
        near(71f, VodSizes.headerWordsBottom())
        near(35.2f, VodSizes.headerStoryH(), 0.001f)
        near(35.8f, VodSizes.headerTextTop(), 0.001f)
        assertTrue(VodSizes.HEADER_BUTTONS_BOTTOM >= VodSizes.DOTS_BOTTOM + VodSizes.DOTS_H + 8f)
        // The backdrop: at most 2.6 header heights wide, right-aligned; Home's fills its card.
        assertEquals(496f, VodSizes.backdropW(VodSizes.homeHeaderW()))
        near(561.6f, VodSizes.backdropW(VodSizes.tabHeaderW()), 0.001f)
        near(470f, VodSizes.storyW(VodSizes.tabHeaderW()))
        near(321.2f, VodSizes.storyW(VodSizes.homeHeaderW()), 0.001f)
        // The story stays inside the text column.
        assertTrue(VodSizes.storyW(VodSizes.homeHeaderW()) <= VodSizes.homeHeaderW() - 2 * VodSizes.HEADER_INSET)
    }

    @Test
    fun theBannersTitleStepsDownForALongName() {
        assertEquals(40f, VodSizes.headerTitleSp(measured = 300f, room = 444f))
        assertEquals(40f, VodSizes.headerTitleSp(measured = 444f, room = 444f))
        assertEquals(30f, VodSizes.headerTitleSp(measured = 444.5f, room = 444f))
        // Both sizes fit the title's box (a line is 1.2 x its size).
        assertTrue(VodSizes.HEADER_TITLE.lineSp <= VodSizes.HEADER_TITLE_H)
        assertTrue(VodSizes.HEADER_TITLE_LONG.lineSp <= VodSizes.HEADER_TITLE_H)
    }

    @Test
    fun theBannersShadingEasesOutToNothing() {
        val across = VodSizes.HEADER_SHADE_ALPHAS
        // Strongest at the left, never rising, clear at the right; no flat rectangle (it changes all the way).
        assertTrue(across.first() >= 0xD0 && across.last() == 0)
        for (i in 1 until across.size) assertTrue(across[i] < across[i - 1])
        // No step bigger than a quarter of the whole: no seam.
        for (i in 1 until across.size) assertTrue(across[i - 1] - across[i] <= 0x40)
        val below = VodSizes.HEADER_SHADE_BOTTOM_ALPHAS
        assertEquals(0, below.first())
        for (i in 1 until below.size) assertTrue(below[i] > below[i - 1])
        // A wide card's gradient the same way: clear at its start, darkest at the bottom.
        assertEquals(0, VodSizes.WIDE_SCRIM_ALPHAS.first())
        assertEquals(VodSizes.WIDE_SCRIM_STOPS.size, VodSizes.WIDE_SCRIM_ALPHAS.size)
        assertEquals(1f, VodSizes.WIDE_SCRIM_STOPS.last())
        // The picture above the words is clear: the gradient starts two fifths of the way down, or lower.
        assertTrue(VodSizes.WIDE_SCRIM_STOPS.first() >= 0.4f)
    }

    @Test
    fun myListsBannerLeavesARowOfPostersWholeUnderIt() {
        assertEquals(246f, VodSizes.fixedRowsTop())
        val room = VodSizes.SCREEN_H - VodSizes.fixedRowsTop()
        assertTrue(room >= VodSizes.rowShownH(RowStyle.POSTER, header = true, large = true))
        // Without buttons its words still start well inside the card.
        val top = VodSizes.MY_LIST_HEADER_H - VodSizes.HEADER_BUTTONS_BOTTOM - VodSizes.headerStoryH() - VodSizes.HEADER_STORY_GAP -
            VodSizes.FACTS.lineSp - VodSizes.HEADER_FACTS_GAP - VodSizes.HEADER_TITLE_H
        assertTrue(top >= 20f)
    }

    @Test
    fun recentChannelsIsAsTallAsTheBanner() {
        val h = VodSizes.RECENT_PAD_TOP + VodSizes.RECENT_HEAD_H + VodSizes.RECENT_FIRST_TOP + VodSizes.recentListH() + VodSizes.RECENT_PAD_BOTTOM
        assertEquals(VodSizes.HEADER_H, h)
        // A row holds its logo tile and its two lines.
        assertTrue(VodSizes.RECENT_LOGO_H + 2 * VodSizes.RECENT_FOCUS_INSET < VodSizes.RECENT_ROW_H)
        assertTrue(VodSizes.RECENT_NAME.lineSp + VodSizes.RECENT_LINE_GAP + VodSizes.RECENT_NOW.lineSp < VodSizes.RECENT_ROW_H - 2 * VodSizes.RECENT_FOCUS_INSET)
        // The programme line is the smaller one.
        assertTrue(VodSizes.RECENT_NOW.sp < VodSizes.RECENT_NAME.sp)
    }

    @Test
    fun recentChannelsMovesOnlyAsFarAsTheChosenRowNeeds() {
        // Three show: rows 0..2 need no move.
        assertEquals(0, VodSizes.recentFirstShown(at = 0, first = 0, n = 8))
        assertEquals(0, VodSizes.recentFirstShown(at = 2, first = 0, n = 8))
        // Down past the last one showing: one row at a time.
        assertEquals(1, VodSizes.recentFirstShown(at = 3, first = 0, n = 8))
        assertEquals(5, VodSizes.recentFirstShown(at = 7, first = 4, n = 8))
        // Back up inside what shows: no move; above it: that row becomes the first.
        assertEquals(5, VodSizes.recentFirstShown(at = 6, first = 5, n = 8))
        assertEquals(4, VodSizes.recentFirstShown(at = 4, first = 5, n = 8))
        // A list that got shorter: never past its end; three or fewer never move.
        assertEquals(1, VodSizes.recentFirstShown(at = 3, first = 5, n = 4))
        assertEquals(0, VodSizes.recentFirstShown(at = 2, first = 3, n = 3))
        assertEquals(0, VodSizes.recentFirstShown(at = 0, first = 0, n = 0))
    }

    @Test
    fun gridIsFiveAcrossTheContentsWidth() {
        assertEquals(154f, VodSizes.GRID_POSTER_W)
        assertEquals(205f, VodSizes.GRID_POSTER_H)
        // It ends at the content's right margin, as the banner does.
        assertEquals(VodSizes.tabHeaderW(), VodSizes.gridW())
        assertEquals(172f, VodSizes.gridPitchX())
        assertEquals(235f, VodSizes.gridPitchY())
        // The grown poster (about its middle) and its ring stay inside the gaps.
        assertTrue(VodSizes.GRID_POSTER_W * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GRID_GAP)
        assertTrue(VodSizes.GRID_POSTER_H * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GRID_ROW_GAP)
        // Two rows of posters fill the viewport under the name line.
        assertTrue(VodSizes.GRID_TOP + 2 * VodSizes.gridPitchY() <= VodSizes.VIEWPORT_H)
    }
}
