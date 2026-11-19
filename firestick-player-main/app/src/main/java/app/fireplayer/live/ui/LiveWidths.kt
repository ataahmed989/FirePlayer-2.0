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
     * The details' top line: the selected channel's logo on a large tile (the user's concept 5: about 2 to 1, half as
     * tall again as a guide row's; the logo is fitted inside with its shape kept), then its name, with the favorite
     * star at the line's end.
     */
    const val DETAIL_LOGO_W = 82
    const val DETAIL_LOGO_H = 42
    /** The room round the logo inside that tile. */
    const val DETAIL_LOGO_PAD_X = 7f
    const val DETAIL_LOGO_PAD_Y = 5f
    /** Between the tile and the channel's name. */
    const val DETAIL_NAME_GAP = 12
    /** Kept free at the line's end for the star (22 dp and the space before it). */
    const val DETAIL_STAR = 34
    /** Between the top line and the programme's title. */
    const val DETAIL_TITLE_GAP = 6

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

    /**
     * The room for the channel's name beside its logo with the groups column open (the narrowest it gets): a name as
     * long as "USA HBO Comedy*" at 16 sp (about 125 dp) fits on one line; a longer one takes a second.
     */
    fun detailNameOpen(): Int = detailsOpen() - DETAIL_LOGO_W - DETAIL_NAME_GAP - DETAIL_STAR

    /** The guide's width with the groups column open, and in the grid. */
    fun guideOpen(): Int = SCREEN - openInset() - GAP
    fun guideGrid(): Int = SCREEN - 2 * GAP

    /** The minutes of programmes a guide [guide] wide shows beside a channel column [channels] wide. */
    fun minutesShown(guide: Int, channels: Int): Float = (guide - channels) / DP_PER_MIN
}
