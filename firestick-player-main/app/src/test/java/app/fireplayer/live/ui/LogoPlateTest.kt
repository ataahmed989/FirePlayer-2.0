package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which plate a channel logo gets, from its pixels (once, as it loads). */
class LogoPlateTest {

    private val clear = 0x00000000

    /** A logo of [n] pixels: [inkShare] of them [ink], the rest see-through. */
    private fun logo(ink: Int, inkShare: Float, n: Int = 400): IntArray {
        val inked = (n * inkShare).toInt()
        return IntArray(n) { if (it < inked) ink else clear }
    }

    @Test fun aBlackWordmarkGetsTheLightPlate() {
        assertTrue(LogoPlate.light(logo(0xFF000000.toInt(), 0.3f)))
        // Dark grey ink too ("CINEMAX" in grey).
        assertTrue(LogoPlate.light(logo(0xFF404040.toInt(), 0.3f)))
        // Half see-through black ink is still dark ink.
        assertTrue(LogoPlate.light(logo(0x80000000.toInt(), 0.5f)))
    }

    @Test fun aWhiteWordmarkKeepsTheSoftPlate() {
        assertFalse(LogoPlate.light(logo(0xFFFFFFFF.toInt(), 0.3f)))
        // Mid grey reads a little better on the soft plate than on the light one.
        assertFalse(LogoPlate.light(logo(0xFF808080.toInt(), 0.3f)))
    }

    @Test fun colouredInkGoesByHowBrightItIs() {
        // Pure blue is dark (it vanishes on the dark wallpaper); pure red is bright enough.
        assertTrue(LogoPlate.light(logo(0xFF0000FF.toInt(), 0.3f)))
        assertFalse(LogoPlate.light(logo(0xFFFF0000.toInt(), 0.3f)))
    }

    @Test fun aLogoWithItsOwnBackgroundIsJustFramed() {
        // A black tile with white letters (a JPEG, say): it brings its own background.
        val px = IntArray(400) { if (it % 10 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertFalse(LogoPlate.light(px))
    }

    @Test fun mixedInkIsAveragedByHowMuchOfEachThereIs() {
        // Mostly white letters with a thin black outline: soft.
        val px = IntArray(400) { when { it < 100 -> 0xFFFFFFFF.toInt(); it < 120 -> 0xFF000000.toInt(); else -> clear } }
        assertFalse(LogoPlate.light(px))
        // Mostly black with a little white: light.
        val dark = IntArray(400) { when { it < 100 -> 0xFF000000.toInt(); it < 110 -> 0xFFFFFFFF.toInt(); else -> clear } }
        assertTrue(LogoPlate.light(dark))
    }

    @Test fun nothingToSeeKeepsTheSoftPlate() {
        assertFalse(LogoPlate.light(IntArray(100)))
        assertFalse(LogoPlate.light(IntArray(0)))
        // Only the pixels counted are looked at.
        assertFalse(LogoPlate.light(logo(0xFF000000.toInt(), 0.3f), count = 0))
    }

    @Test fun thePlatesAreTheTwoColours() {
        assertEquals(LogoPlate.LIGHT, LogoPlate.color(true))
        assertEquals(LogoPlate.SOFT, LogoPlate.color(false))
    }

    // The large logo's plate (LogoBadge): 92 px high with 18 x 12 px of room, so the logo's box is 68 px high.

    private fun badge(w: Int, h: Int) = LogoPlate.badgeWidth(w, h, height = 92, padX = 18f, padY = 12f, minWidth = 92, maxWidth = 208)

    @Test fun theBadgeIsAsWideAsItsLogoNeeds() {
        // Twice as wide as tall: 136 x 68 inside, plus the room each side.
        assertEquals(136 + 36, badge(200, 100))
        assertEquals(136 + 36, badge(50, 25))
    }

    @Test fun aTallLogoGetsTheLeastWidthAndAVeryWideOneTheMost() {
        // Square: 68 x 68 inside, plus the room each side.
        assertEquals(68 + 36, badge(100, 100))
        assertEquals(92, badge(40, 160))
        assertEquals(208, badge(600, 100))
        // Nothing to show (no logo yet): the least width.
        assertEquals(92, badge(0, 0))
    }

    @Test fun aLogoIsFittedWholeAndNeverStretched() {
        // A very wide logo in the widest badge's box (172 x 68): the width decides, and the same scale serves both sides.
        val s = LogoPlate.fitScale(600, 100, 172f, 68f)
        assertEquals(172f / 600, s, 1e-6f)
        assertTrue(100 * s <= 68f)
        // A tall one: the height decides.
        assertEquals(68f / 160, LogoPlate.fitScale(40, 160, 56f, 68f), 1e-6f)
        assertEquals(0f, LogoPlate.fitScale(0, 10, 56f, 68f), 0f)
    }
}
