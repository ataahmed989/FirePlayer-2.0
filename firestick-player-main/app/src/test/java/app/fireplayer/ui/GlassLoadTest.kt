package app.fireplayer.ui

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glass's lighter load while a 4K HDR film plays on a TV short of memory. */
class GlassLoadTest {

    private fun video(
        width: Int,
        height: Int,
        transfer: Int? = null,
        mime: String = MimeTypes.VIDEO_H265,
        codecs: String? = null,
    ): Format {
        val b = Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).setCodecs(codecs)
        if (transfer != null) {
            b.setColorInfo(
                ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(transfer).build(),
            )
        }
        return b.build()
    }

    @Test fun refreshIsCappedAtTwoWithHeavyVideo() {
        assertEquals(8, GlassLoad.refreshPerSec(8, heavy = false))
        assertEquals(4, GlassLoad.refreshPerSec(4, heavy = false))
        assertEquals(2, GlassLoad.refreshPerSec(8, heavy = true))
        assertEquals(2, GlassLoad.refreshPerSec(4, heavy = true))
        // A lower setting stays lower.
        assertEquals(2, GlassLoad.refreshPerSec(2, heavy = true))
        assertEquals(1, GlassLoad.refreshPerSec(1, heavy = true))
        // Out of range, as before: 1 to 8.
        assertEquals(1, GlassLoad.refreshPerSec(0, heavy = false))
        assertEquals(8, GlassLoad.refreshPerSec(20, heavy = false))
    }

    @Test fun probesEveryHalfSecondWithHeavyVideo() {
        assertEquals(100L, GlassLoad.probeMs(heavy = false))
        assertEquals(500L, GlassLoad.probeMs(heavy = true))
    }

    @Test fun theRowSaysWhenTheSettingIsLowered() {
        assertEquals("8 a second · 2 in 4K HDR", GlassLoad.refreshLabel(8, heavy = true))
        assertEquals("4 a second · 2 in 4K HDR", GlassLoad.refreshLabel(4, heavy = true))
        // Not lowered: the row shows the setting's own name.
        assertNull(GlassLoad.refreshLabel(2, heavy = true))
        assertNull(GlassLoad.refreshLabel(8, heavy = false))
    }

    @Test fun smallRamIsUnder1point8GiB() {
        // The Fire TV Stick 4K Max (AFTKRT): 1.7 GB in all.
        assertTrue(GlassLoad.smallRam(1_710_000_000L, lowRam = false))
        assertTrue(GlassLoad.smallRam(1L shl 30, lowRam = false))
        assertFalse(GlassLoad.smallRam(1_932_735_284L, lowRam = false))
        assertFalse(GlassLoad.smallRam(3L shl 30, lowRam = false))
        // Fire OS's low-memory devices always count; an unknown total never does.
        assertTrue(GlassLoad.smallRam(4L shl 30, lowRam = true))
        assertFalse(GlassLoad.smallRam(0L, lowRam = false))
    }

    @Test fun uhdHdrIs4kWithPqHlgOrDolbyVision() {
        assertTrue(GlassLoad.uhdHdr(3840, 2160, C.COLOR_TRANSFER_ST2084, MimeTypes.VIDEO_H265, null))
        assertTrue(GlassLoad.uhdHdr(3840, 2160, C.COLOR_TRANSFER_HLG, MimeTypes.VIDEO_H265, null))
        // A wide 4K film is kept as 3840 x 1600.
        assertTrue(GlassLoad.uhdHdr(3840, 1600, C.COLOR_TRANSFER_ST2084, MimeTypes.VIDEO_H265, null))
        assertTrue(GlassLoad.uhdHdr(3840, 2160, Format.NO_VALUE, MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06"))
        assertTrue(GlassLoad.uhdHdr(3840, 2160, Format.NO_VALUE, MimeTypes.VIDEO_H265, "dvh1.08.06"))
        assertFalse(GlassLoad.uhdHdr(3840, 2160, C.COLOR_TRANSFER_SDR, MimeTypes.VIDEO_H265, null))
        assertFalse(GlassLoad.uhdHdr(3840, 2160, Format.NO_VALUE, MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0"))
        assertFalse(GlassLoad.uhdHdr(1920, 1080, C.COLOR_TRANSFER_ST2084, MimeTypes.VIDEO_H265, null))
        assertFalse(GlassLoad.uhdHdr(1920, 1080, Format.NO_VALUE, MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06"))
    }

    @Test fun heavyIs4kHdrOnASmallTv() {
        val film = video(3840, 2160, C.COLOR_TRANSFER_ST2084)
        assertTrue(GlassLoad.heavy(film, 0, 0, smallRam = true))
        assertFalse(GlassLoad.heavy(film, 0, 0, smallRam = false))
        assertFalse(GlassLoad.heavy(video(1920, 1080, C.COLOR_TRANSFER_ST2084), 1920, 1080, smallRam = true))
        assertFalse(GlassLoad.heavy(video(3840, 2160, C.COLOR_TRANSFER_SDR), 3840, 2160, smallRam = true))
        assertTrue(GlassLoad.heavy(video(3840, 2160, mime = MimeTypes.VIDEO_DOLBY_VISION, codecs = "dvhe.08.06"), 0, 0, true))
        // A track that doesn't give its size: the size shown counts.
        assertTrue(GlassLoad.heavy(video(Format.NO_VALUE, Format.NO_VALUE, C.COLOR_TRANSFER_HLG), 3840, 2160, true))
        assertFalse(GlassLoad.heavy(null, 3840, 2160, smallRam = true))
    }
}
