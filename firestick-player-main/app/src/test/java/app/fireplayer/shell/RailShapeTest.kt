package app.fireplayer.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailShapeTest {

    @Test fun collapsedTheIconsStandWhereTheyDidAttachedOrFloating() {
        // The tabs are as far from the view's edges in both, so nothing moves as Live TV is entered or left.
        assertEquals(RailShape.sideDp(attached = true, expanded = false), RailShape.sideDp(attached = false, expanded = false))
        assertEquals(NavRail.SIDE_DP, RailShape.sideDp(attached = true, expanded = false))
    }

    @Test fun theCollapsedWindowIsExactlyAsWideAsTheTabs() {
        // 58 dp view, 7 dp each side: the 44 dp tabs fill the floating window.
        assertEquals(44, RailShape.windowWidthDp(NavRail.WIDTH_DP))
        assertEquals(NavRail.WIDTH_DP - 2 * RailShape.sideDp(attached = false, expanded = false), RailShape.windowWidthDp(NavRail.WIDTH_DP))
    }

    @Test fun widenedAsAWindowTheTabsKeepClearOfItsEdges() {
        val side = RailShape.sideDp(attached = false, expanded = true)
        assertEquals(RailShape.FLOAT_SIDE_DP + NavRail.WINDOW_PAD_DP, side)
        // The tabs lie inside the window, with the same room each side.
        val window = RailShape.windowWidthDp(NavRail.EXPANDED_DP)
        val tabs = NavRail.EXPANDED_DP - 2 * side
        assertEquals(2 * NavRail.WINDOW_PAD_DP, window - tabs)
    }

    @Test fun widenedOnLiveTvTheStripIsAsItWas() {
        assertEquals(NavRail.SIDE_DP, RailShape.sideDp(attached = true, expanded = true))
    }

    @Test fun theWindowIsInsetFromTheScreensEdges() {
        assertTrue(RailShape.FLOAT_SIDE_DP > 0)
        assertTrue(RailShape.FLOAT_END_DP > 0)
        // And never reaches into the page, which starts where the collapsed rail's view ends.
        assertTrue(RailShape.FLOAT_SIDE_DP + RailShape.windowWidthDp(NavRail.WIDTH_DP) < NavRail.WIDTH_DP)
    }

    @Test fun theMoveBetweenThemStartsAndEndsExactly() {
        assertEquals(14f, RailShape.between(14f, 0f, 0f), 0f)
        assertEquals(0f, RailShape.between(14f, 0f, 1f), 0f)
        assertEquals(7f, RailShape.between(14f, 0f, 0.5f), 0f)
        assertEquals(1080f, RailShape.between(1064f, 1080f, 1f), 0f)
    }
}
