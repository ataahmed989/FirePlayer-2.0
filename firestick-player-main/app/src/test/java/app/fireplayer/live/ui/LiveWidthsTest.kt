package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live TV page's widths across the 960 dp screen, with the groups column open and in the grid. */
class LiveWidthsTest {

    @Test fun theOpenColumnFitsRailColumnMiniatureAndDetailsOnTheScreen() {
        // Rail 58 + column 224 + 12, miniature 368 + 18, details, margin 12.
        assertEquals(294, LiveWidths.openInset())
        assertEquals(268, LiveWidths.detailsOpen())
        assertEquals(
            LiveWidths.SCREEN,
            LiveWidths.RAIL + LiveWidths.COLUMN + LiveWidths.GAP + LiveWidths.MINI + LiveWidths.DETAILS_GAP +
                LiveWidths.detailsOpen() + LiveWidths.GAP,
        )
        // The details keep about the width they had beside the old rail (266 dp): their lines fit as before.
        assertTrue(LiveWidths.detailsOpen() >= 266)
    }

    @Test fun theRailIsASlimStrip() {
        assertTrue(LiveWidths.RAIL in 56..60)
    }

    @Test fun theGuideKeepsRoomForProgrammesBesideTheWiderChannelColumn() {
        assertEquals(654, LiveWidths.guideOpen())
        assertEquals(936, LiveWidths.guideGrid())
        // Beside the groups column: more than an hour and a quarter of programmes.
        assertTrue(LiveWidths.minutesShown(LiveWidths.guideOpen(), LiveWidths.CHANNELS_OPEN) > 75f)
        // In the grid: more than two hours, and the channel column wider than beside the groups column.
        assertTrue(LiveWidths.minutesShown(LiveWidths.guideGrid(), LiveWidths.CHANNELS_GRID) > 120f)
        assertTrue(LiveWidths.CHANNELS_GRID > LiveWidths.CHANNELS_OPEN)
    }

    @Test fun theChannelColumnIsUnderHalfTheGuide() {
        // The programmes, not the names, take most of the guide in both states.
        assertTrue(LiveWidths.CHANNELS_OPEN * 2 < LiveWidths.guideOpen())
        assertTrue(LiveWidths.CHANNELS_GRID * 2 < LiveWidths.guideGrid())
    }
}
