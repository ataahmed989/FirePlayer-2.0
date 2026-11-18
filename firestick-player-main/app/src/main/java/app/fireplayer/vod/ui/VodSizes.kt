package app.fireplayer.vod.ui

import app.fireplayer.shell.NavRail
import app.fireplayer.vod.RowStyle
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every size of the Home, Movies and TV Shows pages, in dp on the 960 x 540 dp screen (design-brief.md §6.3, and the
 * sizing page the user chose them on: its 16 dp gap between the header and the live window, 24 dp right margin, 10 dp
 * between a row's title and its cards, 18 dp under each row and 3 dp white ring). Kept together, without Android
 * types, so they're unit-tested together. x values are page-local (the pages start right of the rail's strip) unless
 * they say screen.
 */
internal object VodSizes {
    const val SCREEN_W = 960f
    const val SCREEN_H = 540f
    /** The tab rail's strip: the pages start here. */
    const val RAIL = NavRail.WIDTH_DP.toFloat()
    /** Where content starts on the screen (user), and in the page. */
    const val CONTENT_SCREEN_X = 94f
    const val PAGE_X = CONTENT_SCREEN_X - RAIL
    const val GAP = 14f
    const val RIGHT_MARGIN = 24f
    /** How much bigger the chosen card is (user: 16%). */
    const val GROW = 0.16f
    /** Genre chips grow like the app's other pills. */
    const val GROW_CHIP = 0.04f
    const val POSTERS_ACROSS = 6.5f
    const val WIDE_ACROSS = 4.5f

    /** A card's width when [n] fit across from the content's left edge to the screen's right edge, [GAP] apart. */
    fun across(n: Float): Float = (SCREEN_W - CONTENT_SCREEN_X + GAP) / n - GAP

    /** Posters: 3:4, 6.5 across: 121 x 162. */
    val POSTER_W: Float = across(POSTERS_ACROSS).roundToInt().toFloat()
    val POSTER_H: Float = (across(POSTERS_ACROSS) * 4f / 3f).roundToInt().toFloat()
    /** Continue Watching cards: 16:9, 4.5 across: 182 x 102. */
    val WIDE_W: Float = across(WIDE_ACROSS).roundToInt().toFloat()
    val WIDE_H: Float = (across(WIDE_ACROSS) * 9f / 16f).roundToInt().toFloat()

    /** Card corners, and the white ring round the chosen card (hugging its outside edge). */
    const val CARD_RADIUS = 10f
    const val RING = 3f

    // Top 10 (Glass Home's ranked shelf): fractions of the poster's height, from its 333 px poster.
    const val RANK_SLOT = 124f / 333f
    const val RANK_TEXT = 230f / 333f
    const val RANK_BASELINE = 2f / 333f
    /** The room "10" may take: the slot and 6 of its px, and the tuck. */
    const val RANK_ROOM = 130f / 333f
    /** How far the number sits behind the poster. */
    const val RANK_TUCK = 5f

    // A row: its title, the gap, the strip of cards (as tall as a grown card) and the gap under it.
    const val ROW_TITLE = 16f
    const val ROW_TITLE_GAP = 10f
    const val ROW_GAP = 18f

    // Genre chips.
    const val CHIP_H = 36f
    const val CHIP_PAD = 16f
    const val CHIP_GAP = 10f

    // The page: the name line, the viewport under it (which clips), the header.
    const val NAME_TOP = 12f
    const val VIEWPORT_TOP = 44f
    const val VIEWPORT_H = SCREEN_H - VIEWPORT_TOP
    const val HEADER_H = 195f
    /** Between the header and the first row. */
    const val HEADER_GAP = 16f
    /** Home's top-right window (the live picture / recent channels), and its gap from the header card. */
    const val WINDOW_W = 330f
    const val WINDOW_GAP = 16f
    /** The backdrop box: at most this many header heights wide, right-aligned in the card. */
    const val BACKDROP_ASPECT = 2.6f

    // Header text.
    const val HEADER_INSET = 26f
    const val HEADER_TITLE_SP = 34f
    const val HEADER_FACTS_SP = 12.5f
    const val HEADER_STORY_SP = 13f
    const val HEADER_STORY_MAX = 470f

    // The See all grid.
    const val GRID_COLUMNS = 6
    const val GRID_ROW_GAP = 30f
    const val GRID_TOP = 16f

    fun cardW(style: RowStyle): Float = if (style == RowStyle.WIDE) WIDE_W else POSTER_W
    fun cardH(style: RowStyle): Float = when (style) {
        RowStyle.WIDE -> WIDE_H
        RowStyle.GENRES -> CHIP_H
        else -> POSTER_H
    }

    /** The rank number's slot left of a Top 10 poster; none elsewhere. */
    fun slot(style: RowStyle): Float = if (style == RowStyle.RANKED) POSTER_H * RANK_SLOT else 0f

    /** One item across: the slot and the card. */
    fun itemW(style: RowStyle): Float = slot(style) + cardW(style)

    /** From one item's left edge to the next one's. */
    fun pitch(style: RowStyle): Float = itemW(style) + GAP

    fun grow(style: RowStyle): Float = if (style == RowStyle.GENRES) GROW_CHIP else GROW

    /** How far the cards after the chosen one move along to make room for it as it grows. */
    fun extra(style: RowStyle): Float = cardW(style) * grow(style)

    /** The strip of cards: as tall as a grown card, the cards centred in it. */
    fun stripH(style: RowStyle): Float = cardH(style) * (1f + grow(style))
    fun cardTop(style: RowStyle): Float = cardH(style) * grow(style) / 2f
    fun stripTop(): Float = ROW_TITLE + ROW_TITLE_GAP

    /** A row's band: title, gap, strip and the gap under it. */
    fun bandH(style: RowStyle): Float = stripTop() + stripH(style) + ROW_GAP

    /** Home's header card: what's left beside the window. */
    fun homeHeaderW(): Float = SCREEN_W - CONTENT_SCREEN_X - RIGHT_MARGIN - WINDOW_GAP - WINDOW_W
    /** Movies' and TV Shows' header card: the content's width. */
    fun tabHeaderW(): Float = SCREEN_W - CONTENT_SCREEN_X - RIGHT_MARGIN
    fun windowX(): Float = PAGE_X + homeHeaderW() + WINDOW_GAP

    /** The first row's top in the page's column (the header above it). */
    fun firstRowTop(): Float = HEADER_H + HEADER_GAP

    fun backdropW(cardW: Float): Float = min(cardW, BACKDROP_ASPECT * HEADER_H)

    /** The header's story: two lines, kept clear of the backdrop's brightest part. */
    fun storyW(cardW: Float): Float = min(cardW - HEADER_INSET - 0.22f * cardW, HEADER_STORY_MAX)

    /** The header text's top. */
    fun headerTextTop(): Float = maxOf(14f, 0.16f * HEADER_H)

    /** The grid: 6 posters across, rows 30 dp apart. */
    fun gridW(): Float = GRID_COLUMNS * POSTER_W + (GRID_COLUMNS - 1) * GAP
    fun gridPitchY(): Float = POSTER_H + GRID_ROW_GAP
}
