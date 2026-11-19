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
        // Season chips (the rows' SEASONS style): as step 1's genre row.
        near(37.44f, VodSizes.stripH(RowStyle.SEASONS))
    }

    @Test
    fun theTextBlockHoldsItsLines() {
        // The title box, the facts row, two lines of story, then the source line, and 8 dp under it.
        near(30f, VodSizes.TITLE_TOP)
        near(122f, VodSizes.titleFactsTop())
        near(150f, VodSizes.titleStoryTop())
        near(37.6f, VodSizes.titleStoryH())
        near(193.6f, VodSizes.titleSourceTop())
        near(VodSizes.TITLE_HEADER_H - 8f, VodSizes.titleSourceTop() + VodSizes.SOURCE.lineSp)
        // The facts and story are the page headers' styles a size up; a name at its large size fits the title box.
        assertEquals(VodSizes.FACTS.copy(sp = 14f), VodSizes.TITLE_FACTS)
        assertEquals(VodSizes.STORY.copy(sp = 14f), VodSizes.TITLE_STORY)
        assertTrue(VodSizes.TITLE_NAME.lineSp <= VodSizes.TITLE_BOX_H && VodSizes.LOGO_W <= VodSizes.TITLE_BOX_W)
    }

    @Test
    fun theStageHoldsTheColumnAndThePodsBesideIt() {
        // The column starts at the content's x; the pods a gap after it, at screen x 334.
        near(276f, VodSizes.stageX())
        near(334f, VodSizes.RAIL + VodSizes.stageX())
        // About two pods and part of a third fit from there to the screen's right edge, also with the first one grown.
        val room = VodSizes.SCREEN_W - (VodSizes.RAIL + VodSizes.stageX())
        val third = room - 2 * VodSizes.podPitch()
        assertTrue(third > 0.2f * VodSizes.POD_W && third < 0.5f * VodSizes.POD_W)
        assertTrue(third - VodSizes.podExtra() > 0.15f * VodSizes.POD_W)
        // A pod: a 16:9 still and its base, which holds its two lines.
        near(VodSizes.POD_W * 9f / 16f, VodSizes.POD_PICTURE_H, 0.5f)
        near(200f, VodSizes.podH())
        val lines = VodSizes.POD_TEXT_TOP + VodSizes.POD_TITLE.lineSp + VodSizes.POD_LINE_GAP + VodSizes.POD_META.lineSp
        assertTrue(lines + 6f <= VodSizes.POD_BASE_H)
        // The chosen pod grows about its middle: its growth and ring stay inside the room above and below, and the
        // ring inside the gap left of it (where the pods' box cuts).
        val grows = VodSizes.podH() * VodSizes.POD_GROW / 2f + VodSizes.RING
        assertTrue(grows <= VodSizes.POD_ROOM)
        assertTrue(VodSizes.RING < VodSizes.POD_GAP)
        near(220f, VodSizes.podsBandH())
        // The season dropdown's row ends above that room; the first button is level with the pods' top.
        assertTrue(VodSizes.SEASON_TOP + VodSizes.SEASON_H <= VodSizes.STAGE_ROW_TOP - VodSizes.POD_ROOM)
        // The column with all its buttons ends about level with the pods, its widest pill inside it.
        near(204f, VodSizes.actionsH())
        assertTrue(kotlin.math.abs(VodSizes.actionsH() - VodSizes.podH()) <= 8f)
        // A focused pill (grown 1.04) stays clear of the pods' box.
        assertTrue(VodSizes.ACTIONS_W * 0.02f < VodSizes.STAGE_GAP - VodSizes.POD_GAP)
        // A pill's words at about 0.55 of their size a letter: "Resume S2 E5 · 12m left" fits at the buttons' size beside
        // its icon, a long "Resume S12 E15 · 1h 12m left" a size down; the wait for an add-on (no icon) a size down too.
        near(166f, VodSizes.pillTextW(icon = true))
        near(188f, VodSizes.pillTextW(icon = false))
        assertTrue(23 * 0.55f * VodSizes.BUTTON.sp <= VodSizes.pillTextW(icon = true))
        assertTrue(26 * 0.55f * VodSizes.BUTTON_LONG.sp <= VodSizes.pillTextW(icon = true))
        assertTrue(29 * 0.55f * VodSizes.BUTTON_LONG.sp <= VodSizes.pillTextW(icon = false))
    }

    @Test
    fun theSeasonListShowsSixRowsAndGlidesTheRest() {
        // Up to six rows show; fewer seasons make a shorter window.
        near(2 * 8f + 2 * 34f + 4f, VodSizes.seasonListH(2))
        near(2 * 8f + 6 * 34f + 5 * 4f, VodSizes.seasonListH(6))
        near(VodSizes.seasonListH(6), VodSizes.seasonListH(30))
        // Under the dropdown, a long list still ends inside the screen.
        val top = VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP + VodSizes.SEASON_H + VodSizes.SEASON_LIST_GAP
        assertTrue(top + VodSizes.seasonListH(30) <= VodSizes.SCREEN_H - VodSizes.SEASON_LIST_INSET)
        // A short list never moves.
        assertEquals(0, VodSizes.seasonListFirst(3, 0, 4))
        assertEquals(0, VodSizes.seasonListFirst(5, 0, 6))
        // A long one moves only as far as the chosen row needs, and never past its ends.
        assertEquals(0, VodSizes.seasonListFirst(5, 0, 12))
        assertEquals(1, VodSizes.seasonListFirst(6, 0, 12))
        assertEquals(1, VodSizes.seasonListFirst(3, 1, 12))
        assertEquals(0, VodSizes.seasonListFirst(0, 1, 12))
        assertEquals(6, VodSizes.seasonListFirst(11, 4, 12))
        // Opened on season 9 of 12 (asked for with that row in the middle): rows 6 to 11 show.
        assertEquals(5, VodSizes.seasonListFirst(8, 8 - VodSizes.SEASON_LIST_ROWS / 2, 12))
        assertEquals(0, VodSizes.seasonListFirst(1, 1 - VodSizes.SEASON_LIST_ROWS / 2, 12))
        assertEquals(6, VodSizes.seasonListFirst(11, 11 - VodSizes.SEASON_LIST_ROWS / 2, 12))
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
        assertTrue(VodSizes.episodeWindow(200, 120, 0) > 120 + VodSizes.POD_BIND_AFTER)
    }

    @Test
    fun theHeaderStartsWhereTheRowsDo() {
        // The text block and the action column start at the content's x (screen 94 dp), as the rows' strips (PAGE_X) do.
        assertEquals(VodSizes.PAGE_X, VodSizes.titleTextX())
        assertEquals(VodSizes.CONTENT_SCREEN_X, VodSizes.RAIL + VodSizes.titleTextX())
        // The facts row (the tab headers' width) then ends 24 dp from the screen's right edge; the title box inside it.
        assertEquals(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.RAIL + VodSizes.titleTextX() + VodSizes.tabHeaderW())
        assertTrue(VodSizes.TITLE_BOX_W <= VodSizes.tabHeaderW() && VodSizes.TITLE_TEXT_W <= VodSizes.tabHeaderW())
    }

    @Test
    fun aFilmsMoreLikeThisFitsBesideTheColumn() {
        // Its posters start level with the first button, and its band ends on screen.
        near(VodSizes.STAGE_ROW_TOP, VodSizes.filmMoreTop() + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER))
        val end = VodSizes.TITLE_HEADER_H + VodSizes.filmMoreTop() + VodSizes.bandH(RowStyle.POSTER)
        assertTrue(end <= VodSizes.SCREEN_H)
        // The stage holds the band, and the whole column with the circles' names.
        assertTrue(VodSizes.filmStageH() >= VodSizes.filmMoreTop() + VodSizes.bandH(RowStyle.POSTER))
        assertTrue(VodSizes.filmStageH() >= VodSizes.STAGE_ROW_TOP + VodSizes.actionsH() + VodSizes.CIRCLE_NAME_ROOM)
        assertTrue(VodSizes.TITLE_HEADER_H + VodSizes.STAGE_ROW_TOP + VodSizes.actionsH() + VodSizes.CIRCLE_NAME_ROOM <= VodSizes.SCREEN_H)
    }

    @Test
    fun aShowGlidesUpForMoreLikeThis() {
        // More Like This starts under the stage: its title shows above the screen's bottom (a hint), its posters don't.
        val moreTop = VodSizes.TITLE_HEADER_H + VodSizes.showStageH()
        near(504f, moreTop)
        assertTrue(moreTop + VodSizes.ROW_TITLE_STYLE.lineSp < VodSizes.SCREEN_H)
        assertTrue(moreTop + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER) >= VodSizes.SCREEN_H)
        val moreBottom = moreTop + VodSizes.bandH(RowStyle.POSTER)
        val rowTop = VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP
        val g = VodSizes.glide(moreBottom, rowTop)
        // Glided: More Like This wholly on screen, and all the text above the season dropdown's row gone.
        assertTrue(moreBottom - g <= VodSizes.SCREEN_H)
        assertTrue(g >= rowTop - VodSizes.GLIDE_KEEP_TOP)
        assertTrue(VodSizes.titleSourceTop() + VodSizes.SOURCE.lineSp - g <= 0.01f)
        // A page with nothing below never glides past its own top.
        assertEquals(0f, VodSizes.glide(100f, 10f))
    }

    @Test
    fun theTitlePagesPicturesStayInBudget() {
        // The backdrop at half the screen in ARGB (its fade baked in); a pod's still at its shown size in RGB_565, and
        // the handful bound at once (the one before the chosen pod, it, and the ones after) well under 2 MB.
        assertEquals(2_073_600, VodSizes.TITLE_ART_W * VodSizes.TITLE_ART_H * 4)
        val still = Math.ceil(VodSizes.POD_W * 2.0).toInt() * Math.ceil(VodSizes.POD_PICTURE_H * 2.0).toInt() * 2
        assertEquals(312_576, still)
        assertTrue(still * (2 + VodSizes.POD_BIND_AFTER) < 2_000_000)
        // The logo is kept whole inside 800 x 180 px (the title box's 440 x 84 dp on the TV is 880 x 168), never enlarged.
        assertEquals(800 to 180, ImageFit.inside(1600, 360, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(800 to 160, ImageFit.inside(1000, 200, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(200 to 50, ImageFit.inside(200, 50, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(1 to 1, ImageFit.inside(0, 50, 400, 155))
    }
}
