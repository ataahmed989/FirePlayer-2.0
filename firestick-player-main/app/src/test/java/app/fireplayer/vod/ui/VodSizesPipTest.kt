package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live picture's sizes on Home and Search (DECISIONS.md of 2026-11-19). */
class VodSizesPipTest {

    @Test fun searchsPictureIsAQuarterOfTheScreenWideAt16By9InTheTopRight() {
        assertEquals(240f, VodSizes.SEARCH_PIP_W)
        assertEquals(VodSizes.SEARCH_PIP_W * 9f / 16f, VodSizes.SEARCH_PIP_H, 0.001f)
        // Right-aligned at the page's right margin, beside the page's name.
        assertEquals(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.RAIL + VodSizes.searchPipX() + VodSizes.SEARCH_PIP_W, 0.001f)
        assertEquals(VodSizes.NAME_TOP, VodSizes.SEARCH_PIP_TOP)
    }

    @Test fun searchsPictureEndsAboveTheHistoryAndTheResults() {
        // Nothing of the page is drawn over the picture: what's under the bar starts below it.
        assertTrue(VodSizes.SEARCH_PIP_TOP + VodSizes.SEARCH_PIP_H < VodSizes.searchListTop())
    }

    @Test fun theBarIsShortenedSoTheBinClearsThePicture() {
        val barEnd = VodSizes.PAGE_X + VodSizes.searchBarW(pip = true) + VodSizes.SEARCH_TRASH_GAP + VodSizes.SEARCH_TRASH
        assertEquals(VodSizes.searchPipX() - VodSizes.SEARCH_PIP_GAP, barEnd, 0.001f)
        // Without the picture, the bar is as it was.
        assertEquals(VodSizes.searchBarW(), VodSizes.searchBarW(pip = false), 0.001f)
        assertTrue(VodSizes.searchBarW(pip = true) in 400f..VodSizes.searchBarW())
    }

    @Test fun onHomeTheStripSitsOverThePicturesBottomEdge() {
        // The window is the picture's shape, so there's no band: the strip lies over the picture, inside the window.
        val stripTop = VodSizes.HEADER_H - VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_H
        assertTrue(stripTop > VodSizes.HEADER_H * 3f / 4f)
        val buttons = 3 * VodSizes.PIP_BUTTON + 2 * VodSizes.PIP_BUTTON_GAP
        val words = VodSizes.PIP_WINDOW_W - 2 * VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_PAD_L - VodSizes.PIP_STRIP_PAD_R -
            VodSizes.PIP_WORDS_GAP - buttons
        assertTrue("room for the channel and programme on Home: $words dp", words >= 250f)
    }

    @Test fun theStripsButtonsFitItAndItsWordsHaveRoom() {
        assertTrue(VodSizes.PIP_BUTTON <= VodSizes.PIP_STRIP_H)
        assertTrue(VodSizes.PIP_ICON < VodSizes.PIP_BUTTON)
        val buttons = 3 * VodSizes.PIP_BUTTON + 2 * VodSizes.PIP_BUTTON_GAP
        val words = VodSizes.SEARCH_PIP_W - 2 * VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_PAD_L - VodSizes.PIP_STRIP_PAD_R -
            VodSizes.PIP_WORDS_GAP - buttons
        assertTrue("room for the channel's name on Search: $words dp", words >= 100f)
    }
}
