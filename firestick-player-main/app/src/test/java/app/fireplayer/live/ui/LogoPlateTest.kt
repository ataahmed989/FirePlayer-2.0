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
}
