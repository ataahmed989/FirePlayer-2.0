package app.fireplayer.live.ui

import app.fireplayer.shell.NavRail

/**
 * The Live TV page's widths across the screen (dp on the 960 x 540 dp screen), kept together so they're unit-tested
 * together: with the groups column open, the tab rail's strip, the column, the miniature and the details beside it fit
 * the screen; the guide under them keeps room for its channel names and the programmes, open and in the grid.
 */
internal object LiveWidths {
    const val SCREEN = 960
    /** The tab rail's slim strip at the screen's left edge, beside the groups column. */
    const val RAIL = NavRail.WIDTH_DP
    /** The groups column: names at 16 sp as long as "USA | Documentaries" (about 153 dp) fit whole in its 164 dp. */
    const val COLUMN = 224
    /** The miniature (368 x 207 dp, user: kept this size). */
    const val MINI = 368
    /** The content's margin at the screen's edges, and between the groups column and the content. */
    const val GAP = 12
    /** Between the miniature and its details. */
    const val DETAILS_GAP = 18

    /**
     * The guide's channel column beside the groups column: "USA Cinemax Action West" at 15.5 sp (about 186 dp in
     * Figtree) fits beside a three-digit number and the logo. In the grid it's wider, so long names show more.
     */
    const val CHANNELS_OPEN = 288
    const val CHANNELS_GRID = 344
    /** The guide's time scale. */
    const val DP_PER_MIN = 4.6f

    /** Where the content starts with the groups column open. */
    fun openInset(): Int = RAIL + COLUMN + GAP

    /** The details' width with the groups column open: what the screen leaves beside the miniature. */
    fun detailsOpen(): Int = SCREEN - openInset() - MINI - DETAILS_GAP - GAP

    /** The guide's width with the groups column open, and in the grid. */
    fun guideOpen(): Int = SCREEN - openInset() - GAP
    fun guideGrid(): Int = SCREEN - 2 * GAP

    /** The minutes of programmes a guide [guide] wide shows beside a channel column [channels] wide. */
    fun minutesShown(guide: Int, channels: Int): Float = (guide - channels) / DP_PER_MIN
}
