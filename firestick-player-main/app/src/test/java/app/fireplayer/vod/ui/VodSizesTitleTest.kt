package app.fireplayer.vod.ui

import app.fireplayer.shell.TextStyle
import app.fireplayer.ui.ImageFit
import app.fireplayer.vod.RowStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title page's geometry (design-brief.md §6.4) and the shared look values step 1's pages moved here unchanged. */
class VodSizesTitleTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.01f) = assertEquals(expected, actual, tolerance)

    @Test
    fun stepOnesRowsAreUnchanged() {
        // The strips, card tops and bands step 1 had before the item pivot was generalised.
        near(187.92f, VodSizes.stripH(RowStyle.POSTER))
        near(187.92f, VodSizes.stripH(RowStyle.RANKED))
        near(118.32f, VodSizes.stripH(RowStyle.WIDE))
        near(37.44f, VodSizes.stripH(RowStyle.GENRES))
        near(12.96f, VodSizes.cardTop(RowStyle.POSTER))
        near(8.16f, VodSizes.cardTop(RowStyle.WIDE))
        near(0.72f, VodSizes.cardTop(RowStyle.GENRES))
        near(231.92f, VodSizes.bandH(RowStyle.POSTER))
        near(162.32f, VodSizes.bandH(RowStyle.WIDE))
        near(81.44f, VodSizes.bandH(RowStyle.GENRES))
        // Each card grows about the middle of its picture (the measured height's half, as before).
        near(81f, VodSizes.pivotY(RowStyle.POSTER))
        near(51f, VodSizes.pivotY(RowStyle.WIDE))
        near(18f, VodSizes.pivotY(RowStyle.GENRES))
        near(0.04f, VodSizes.GROW_CHIP, 0.0001f)
    }

    @Test
    fun theMovedLookValuesKeepTheirValues() {
        assertEquals(36f, VodSizes.CHIP_H)
        assertEquals(16f, VodSizes.CHIP_PAD)
        assertEquals(10f, VodSizes.CHIP_GAP)
        // The season shown: the accent (#3D8BFF in colors.xml) at 40%, the Episodes sheet's 0x663D8BFF.
        assertEquals(0x663D8BFF, (VodSizes.CHIP_SELECTED_ALPHA shl 24) or 0x3D8BFF)
        assertEquals(14f, VodSizes.PAGE_OMEGA)
        assertEquals(0.5f, VodSizes.PAGE_SETTLE)
        assertEquals(650L, VodSizes.HERO_FADE_MS)
        assertEquals(0.4f, VodSizes.ART_CROP_Y)
        assertEquals(3_500L, VodSizes.NOTICE_MS)
        assertEquals(450L, VodSizes.LIST_CLOSE_MS)
        assertEquals(0xFFF2F4F7.toInt(), VodSizes.RING_COLOR)
        assertEquals(0xFF26313F.toInt(), VodSizes.HOLDER_TOP)
        assertEquals(0xFF141A22.toInt(), VodSizes.HOLDER_BOTTOM)
        assertEquals(0xC7000000.toInt(), VodSizes.SCRIM_BOTTOM)
        assertEquals(0x47FFFFFF, VodSizes.TRACK_COLOR)
        assertEquals(0x59FFFFFF, VodSizes.DOT_OFF)
        assertEquals(TextStyle(16f, TextStyle.Weight.BOLD, TextStyle.Ink.OWN, 0xFFE3E9F0.toInt()), VodSizes.ROW_TITLE_STYLE)
        assertEquals(TextStyle(12.5f, TextStyle.Weight.REGULAR, TextStyle.Ink.OWN, 0xFFC3CFDC.toInt()), VodSizes.FACTS)
        assertEquals(TextStyle(13f, TextStyle.Weight.REGULAR, TextStyle.Ink.MUTED, lineGap = 2f), VodSizes.STORY)
        assertEquals(TextStyle(34f, TextStyle.Weight.BOLD), VodSizes.HEADER_TITLE)
        assertEquals(TextStyle(12.5f, TextStyle.Weight.BOLD, TextStyle.Ink.OWN, 0xFFFFFFFF.toInt()), VodSizes.CARD_NAME)
        assertEquals(TextStyle(10.5f, TextStyle.Weight.SEMIBOLD, TextStyle.Ink.OWN, 0xFFD6DEE8.toInt()), VodSizes.CARD_CAPTION)
        // The header's baked fade: step 1's eased stops.
        assertArrayEquals(intArrayOf(0x00, 0x24, 0x80, 0xDB, 0xFF), VodSizes.FADE_ALPHAS)
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), VodSizes.FADE_STOPS, 0f)
        assertEquals(0.45f, VodSizes.HEADER_FADE_LEFT_TO)
        assertEquals(0.65f, VodSizes.HEADER_FADE_BOTTOM_FROM)
    }

    @Test
    fun episodeCardsAreWideCardsWithTwoLinesUnder() {
        assertEquals(VodSizes.WIDE_W, VodSizes.cardW(RowStyle.EPISODE))
        assertEquals(138f, VodSizes.cardH(RowStyle.EPISODE))
        assertEquals(102f, VodSizes.pictureH(RowStyle.EPISODE))
        near(51f, VodSizes.pivotY(RowStyle.EPISODE))
        // Grown 16% about the still's middle: 8.16 dp above it, its captions 13.92 dp further down.
        near(8.16f, VodSizes.cardTop(RowStyle.EPISODE))
        near(160.08f, VodSizes.stripH(RowStyle.EPISODE))
        near(196f, VodSizes.pitch(RowStyle.EPISODE))
        near(29.12f, VodSizes.extra(RowStyle.EPISODE), 0.001f)
        // The two caption lines fit under the still, with their gaps.
        val lines = VodSizes.EPISODE_CAPTION_GAP + VodSizes.EPISODE_TITLE.lineSp + VodSizes.EPISODE_LINE_GAP + VodSizes.EPISODE_META.lineSp
        assertTrue(VodSizes.pictureH(RowStyle.EPISODE) + lines <= VodSizes.EPISODE_ITEM_H)
        // Season pills are chips: as step 1's genre row, without the title line.
        near(37.44f, VodSizes.stripH(RowStyle.SEASONS))
        near(47.44f, VodSizes.pillsBandH())
        near(178.08f, VodSizes.episodesBandH())
    }

    @Test
    fun theHeaderBlockHoldsItsLinesAndButtons() {
        val buttons = VodSizes.titleButtonsTop()
        // Title box, facts row, three lines of story, the cast and source lines, then the buttons and the circles' names.
        near(34f, VodSizes.TITLE_TOP)
        near(106f, VodSizes.titleFactsTop())
        near(136f, VodSizes.titleStoryTop())
        near(52.8f, VodSizes.titleStoryH())
        near(244.2f, buttons)
        val labelRoom = 4f + 11f * TextStyle.LINE + 2f
        assertTrue(buttons + VodSizes.BUTTON_H + labelRoom <= VodSizes.TITLE_HEADER_H)
        // The widest button row (a show mid-episode) fits the content's width.
        val perChar = 6.9f
        val widest = (24 * perChar + 2 * VodSizes.BUTTON_PAD + VodSizes.BUTTON_ICON + VodSizes.BUTTON_ICON_GAP) +
            (19 * perChar + 2 * VodSizes.BUTTON_PAD + VodSizes.BUTTON_ICON + VodSizes.BUTTON_ICON_GAP) +
            (8 * perChar + 2 * VodSizes.BUTTON_PAD + VodSizes.BUTTON_ICON + VodSizes.BUTTON_ICON_GAP) +
            (7 * perChar + 2 * VodSizes.BUTTON_PAD) + 2 * VodSizes.BUTTON_H + 5 * VodSizes.BUTTON_GAP
        assertTrue(widest < VodSizes.tabHeaderW())
    }

    @Test
    fun aLongSeasonIsHeldAWindowAtATime() {
        // A usual season: all its cards.
        assertEquals(12, VodSizes.episodeWindow(12, 0, 0))
        assertEquals(0, VodSizes.episodeWindow(0, 0, 0))
        // A daily show's 200: 40 at first, 40 more once focus is within 10 of the last held, never fewer than held.
        assertEquals(40, VodSizes.episodeWindow(200, 0, 0))
        assertEquals(40, VodSizes.episodeWindow(200, 29, 40))
        assertEquals(80, VodSizes.episodeWindow(200, 30, 40))
        assertEquals(80, VodSizes.episodeWindow(200, 31, 80))
        assertEquals(200, VodSizes.episodeWindow(200, 150, 0))
        assertEquals(100, VodSizes.episodeWindow(100, 95, 80))
        // The Play target far into the season: held from the start.
        assertTrue(VodSizes.episodeWindow(200, 120, 0) > 120 + VodSizes.EPISODE_BIND_AFTER)
    }

    @Test
    fun theHeaderStartsWhereTheRowsDo() {
        // The text column and the buttons start at the content's x (screen 94 dp), as the rows' strips (PAGE_X) do.
        assertEquals(VodSizes.PAGE_X, VodSizes.titleTextX())
        assertEquals(VodSizes.CONTENT_SCREEN_X, VodSizes.RAIL + VodSizes.titleTextX())
        // The facts row (the tab headers' width) then ends 24 dp from the screen's right edge; the title box inside it.
        assertEquals(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.RAIL + VodSizes.titleTextX() + VodSizes.tabHeaderW())
        assertTrue(VodSizes.TITLE_BOX_W <= VodSizes.tabHeaderW() && VodSizes.TITLE_TEXT_W <= VodSizes.tabHeaderW())
    }

    @Test
    fun aFilmsMoreLikeThisFitsUnderTheButtons() {
        val end = VodSizes.TITLE_HEADER_H + VodSizes.bandH(RowStyle.POSTER)
        near(533.92f, end)
        assertTrue(end <= VodSizes.SCREEN_H)
    }

    @Test
    fun aShowGlidesUpForMoreLikeThis() {
        val buttons = VodSizes.titleButtonsTop()
        // With season pills: More Like This' title starts just above the screen's bottom (cut there, a hint).
        val moreTop = VodSizes.TITLE_HEADER_H + VodSizes.pillsBandH() + VodSizes.episodesBandH()
        near(527.52f, moreTop)
        assertTrue(moreTop < VodSizes.SCREEN_H && moreTop + VodSizes.ROW_TITLE_STYLE.lineSp > VodSizes.SCREEN_H)
        val moreBottom = moreTop + VodSizes.bandH(RowStyle.POSTER)
        val g = VodSizes.glide(moreBottom, buttons)
        // Glided: the buttons 14 dp from the top, the text above them gone, More Like This wholly on screen.
        near(buttons - VodSizes.GLIDE_KEEP_TOP, g)
        assertTrue(moreBottom - g <= VodSizes.SCREEN_H)
        assertTrue(VodSizes.titleSourceTop() + VodSizes.SOURCE.lineSp - g <= 0.01f)
        // One season (no pills): the same glide.
        val oneBottom = VodSizes.TITLE_HEADER_H + VodSizes.episodesBandH() + VodSizes.bandH(RowStyle.POSTER)
        near(buttons - VodSizes.GLIDE_KEEP_TOP, VodSizes.glide(oneBottom, buttons))
        // A page with nothing below never glides past its own top.
        assertEquals(0f, VodSizes.glide(100f, 10f))
    }

    @Test
    fun theTitlePagesPicturesStayInBudget() {
        // The backdrop at half the screen in ARGB (its fade baked in), an episode still at its shown size in RGB_565.
        assertEquals(2_073_600, VodSizes.TITLE_ART_W * VodSizes.TITLE_ART_H * 4)
        assertEquals(148_512, Math.ceil(VodSizes.WIDE_W * 2.0).toInt() * Math.ceil(VodSizes.WIDE_H * 2.0).toInt() * 2)
        // The logo is kept whole inside 400 x 155 px, never enlarged.
        assertEquals(400 to 155, ImageFit.inside(800, 310, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(400 to 80, ImageFit.inside(500, 100, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(200 to 50, ImageFit.inside(200, 50, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(1 to 1, ImageFit.inside(0, 50, 400, 155))
    }
}
