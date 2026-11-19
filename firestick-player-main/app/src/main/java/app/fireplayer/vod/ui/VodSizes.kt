package app.fireplayer.vod.ui

import app.fireplayer.shell.NavRail
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.TextStyle.Ink
import app.fireplayer.shell.TextStyle.Weight
import app.fireplayer.shell.Tokens
import app.fireplayer.vod.RowStyle
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Films & Shows' one look holder: every size, text style, colour and motion value of the Home, Movies and TV Shows
 * pages and the title page, in dp on the 960 x 540 dp screen (design-brief.md §6.3-6.4, and the sizing page the user
 * chose them on: its 16 dp gap between the header and the live window, 24 dp right margin, 10 dp between a row's
 * title and its cards, 18 dp under each row and 3 dp white ring). The app-wide colours, faces and focus growth are in
 * [Tokens] and the surfaces in [app.fireplayer.ui.Glass]; what only Films & Shows uses is here, so a change made here
 * reaches every page that shows it. Kept without Android types, so it's unit-tested. x values are page-local (the
 * pages start right of the rail's strip) unless they say screen.
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
    /** Chips (genres, seasons) grow like the app's other pills. */
    const val GROW_CHIP = Tokens.GROW_PILL - 1f
    const val POSTERS_ACROSS = 6.5f
    const val WIDE_ACROSS = 4.5f

    /** A card's width when [n] fit across from the content's left edge to the screen's right edge, [GAP] apart. */
    fun across(n: Float): Float = (SCREEN_W - CONTENT_SCREEN_X + GAP) / n - GAP

    /** Posters: 3:4, 6.5 across: 121 x 162. */
    val POSTER_W: Float = across(POSTERS_ACROSS).roundToInt().toFloat()
    val POSTER_H: Float = (across(POSTERS_ACROSS) * 4f / 3f).roundToInt().toFloat()
    /** Continue Watching cards: 16:9, 4.5 across: 182 x 102. A show's episode stills on the title page too. */
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
    /** The rank number: solid white, a little tighter (Glass Home). */
    const val RANK_COLOR = 0xFFFFFFFF.toInt()
    const val RANK_SPACING = -0.06f

    // A row: its title, the gap, the strip of cards (as tall as a grown card) and the gap under it.
    const val ROW_TITLE = 16f
    const val ROW_TITLE_GAP = 10f
    const val ROW_GAP = 18f

    // Chips (genres; a show's seasons on the title page).
    const val CHIP_H = 36f
    const val CHIP_PAD = 16f
    const val CHIP_GAP = 10f
    /** The rim of a flat surface in the glass look (chips, See all cards), dp (at least a pixel). */
    const val FLAT_RIM = 1f
    /**
     * The season shown (not focused): the Episodes sheet's fill and ring (EpisodesSheet.stylePill), both the accent
     * ([Tokens.accent]); the fill at this alpha (40%).
     */
    const val CHIP_SELECTED_ALPHA = 0x66
    const val CHIP_SELECTED_RIM = 1.5f

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
    /** The backdrop box: at most this many header heights wide, right-aligned in the card, this far inside its edge. */
    const val BACKDROP_ASPECT = 2.6f
    const val HEADER_ART_INSET = 1f
    /** The clock's fixed width beside the page's name, and the gap before it. */
    const val CLOCK_W = 300f
    const val CLOCK_GAP = 14f

    // Header text.
    const val HEADER_INSET = 26f
    const val HEADER_TITLE_SP = 34f
    const val HEADER_FACTS_SP = 12.5f
    const val HEADER_STORY_SP = 13f
    const val HEADER_STORY_MAX = 470f
    const val HEADER_FACTS_GAP = 6f
    const val HEADER_STORY_GAP = 8f
    /** The rotation's dots: the box, the current one's bar, the gap between, the bottom margin. */
    const val DOTS_W = 130f
    const val DOTS_H = 6f
    const val DOT_BAR = 16f
    const val DOT_GAP = 5f
    const val DOTS_BOTTOM = 12f
    const val DOT_ON = 0xFFFFFFFF.toInt()
    const val DOT_OFF = 0x59FFFFFF

    // The See all grid.
    const val GRID_COLUMNS = 6
    const val GRID_ROW_GAP = 30f
    const val GRID_TOP = 16f

    // "Couldn't load titles": the message, Try again (its padding) and the gap between.
    const val TRY_PAD_X = 22f
    const val TRY_PAD_Y = 10f
    const val TRY_GAP = 18f

    // Notices ("Playback comes next"): padding, distance from the bottom, how long they stay.
    const val NOTICE_PAD_X = 16f
    const val NOTICE_PAD_Y = 10f
    const val NOTICE_BOTTOM = 30f
    const val NOTICE_MS = 3_500L

    // The small window (hold OK): kept this far inside the page; closed this long after + Add to list.
    const val HOLD_INSET = 12f
    const val LIST_CLOSE_MS = 450L

    // Cards: text inside a wide card, the progress bar, badges.
    const val CARD_TEXT_SIDE = 8f
    const val CARD_BOTTOM = 9f
    const val CARD_BOTTOM_BAR = 15f
    const val CARD_LINE_GAP = 2f
    const val BAR_BOTTOM = 6f
    const val BAR_H = 3f
    const val BAR_RADIUS = 1.5f
    const val SCRIM_FROM = 0.35f
    const val BADGE_PAD_X = 7f
    const val BADGE_PAD_Y = 2f
    const val BADGE_INSET = 6f
    const val BADGE_RADIUS = 8f
    const val HOLDER_LINES = 4
    /** The white of the focus ring (the sizing page's focus white). */
    const val RING_COLOR = 0xFFF2F4F7.toInt()
    /** The Episodes sheet's card placeholder. */
    const val HOLDER_TOP = 0xFF26313F.toInt()
    const val HOLDER_BOTTOM = 0xFF141A22.toInt()
    const val SCRIM_BOTTOM = 0xC7000000.toInt()
    const val TRACK_COLOR = 0x47FFFFFF
    const val SEE_ALL = "See all ›"

    // Home's recent channels window.
    const val RECENT_PAD_X = 16f
    const val RECENT_PAD_TOP = 14f
    const val RECENT_PAD_BOTTOM = 12f
    const val RECENT_ICON = 16f
    const val RECENT_HEAD_H = 20f
    const val RECENT_LOGO_W = 52f
    const val RECENT_LOGO_H = 32f
    const val RECENT_LOGO_PX = 76f
    const val RECENT_TEXT_W = 220f
    const val RECENT_ROW_H = 42f
    const val RECENT_ICON_GAP = 8f
    const val RECENT_LINE_GAP = 2f
    const val RECENT_LOGO_GAP = 12f
    const val RECENT_FIRST_TOP = 8f
    const val RECENT_ROW_GAP = 4f
    const val RECENT_EMPTY_TOP = 14f

    // Motion.
    /** The page's and See all's glide (and the title page's): a Spring this quick, settled this close (px). */
    const val PAGE_OMEGA = 14f
    const val PAGE_SETTLE = 0.5f
    /** A header's backdrop crossfade (Glass Home's hero fade), its text fade, and the follow and warm-ahead waits. */
    const val HERO_FADE_MS = 650L
    const val HEADER_TEXT_FADE_MS = 120L
    const val FOLLOW_ART_MS = 500L
    const val WARM_AHEAD_MS = 1_500L
    /** Faces sit high in most backdrops: the crop keeps a little more of the top. */
    const val ART_CROP_Y = 0.4f
    /** The backdrop's baked fade: alpha at the eased stops (clear → solid), and the step 1 header's spans. */
    val FADE_STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    val FADE_ALPHAS = intArrayOf(0x00, 0x24, 0x80, 0xDB, 0xFF)
    const val HEADER_FADE_LEFT_TO = 0.45f
    const val HEADER_FADE_BOTTOM_FROM = 0.65f

    // ---- Text styles (one place; Tokens.label makes the views) ----

    val PAGE_NAME = TextStyle(20f, Weight.BOLD)
    val CLOCK = TextStyle(13f, Weight.REGULAR, Ink.MUTED)
    val HEADER_TITLE = TextStyle(HEADER_TITLE_SP, Weight.BOLD)
    /** The header's facts line (and the title page's): the sizing page's pale blue-grey. */
    val FACTS = TextStyle(HEADER_FACTS_SP, Weight.REGULAR, Ink.OWN, 0xFFC3CFDC.toInt())
    val STORY = TextStyle(HEADER_STORY_SP, Weight.REGULAR, Ink.MUTED, lineGap = 2f)
    /** Row titles: the sizing page's light grey-blue. */
    val ROW_TITLE_STYLE = TextStyle(ROW_TITLE, Weight.BOLD, Ink.OWN, 0xFFE3E9F0.toInt())
    val CHIP = TextStyle(14f, Weight.SEMIBOLD)
    val EMPTY = TextStyle(18f, Weight.SEMIBOLD)
    val TRY_AGAIN = TextStyle(15f, Weight.SEMIBOLD)
    val NOTICE = TextStyle(15f, Weight.REGULAR)
    val CARD_NAME = TextStyle(12.5f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val CARD_CAPTION = TextStyle(10.5f, Weight.SEMIBOLD, Ink.OWN, 0xFFD6DEE8.toInt())
    val CARD_HOLDER = TextStyle(12.5f, Weight.SEMIBOLD, Ink.MUTED)
    val SEE_ALL_STYLE = TextStyle(14f, Weight.SEMIBOLD)
    val BADGE = TextStyle(10f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val RECENT_HEAD = TextStyle(14f, Weight.SEMIBOLD)
    val RECENT_NAME = TextStyle(14f, Weight.SEMIBOLD)
    val RECENT_NOW = TextStyle(12.5f, Weight.REGULAR, Ink.MUTED)
    val RECENT_EMPTY = TextStyle(13f, Weight.REGULAR, Ink.MUTED)

    // ---- The title page (design-brief.md §6.4, layout A) ----

    /** The header block: title box, facts row, story, cast line, source line, buttons (fixed, so words only redraw). */
    const val TITLE_TOP = 34f
    const val TITLE_BOX_W = 560f
    const val TITLE_BOX_H = 64f
    /** The logo fits inside this box (left and bottom aligned in the title box); decoded at most this size, px. */
    const val LOGO_W = 320f
    const val LOGO_DECODE_W = 400
    const val LOGO_DECODE_H = 155
    val TITLE_NAME = TextStyle(32f, Weight.BOLD)
    /** A name too wide for the title box at 32 sp: one line at 24 sp, cut short. */
    val TITLE_NAME_LONG = TextStyle(24f, Weight.BOLD)
    const val FACTS_GAP = 8f
    const val FACTS_ROW_H = 22f
    val CALLOUT = TextStyle(11.5f, Weight.SEMIBOLD)
    const val CALLOUT_PAD = 9f
    const val CALLOUT_GAP = 10f
    const val STORY_GAP = 8f
    const val STORY_LINES = 3
    const val TITLE_TEXT_W = 520f
    const val CAST_GAP = 6f
    val CAST = TextStyle(12.5f, Weight.REGULAR, Ink.MUTED)
    const val SOURCE_GAP = 6f
    val SOURCE = TextStyle(12f, Weight.REGULAR, Ink.MUTED)
    const val BUTTONS_GAP_ABOVE = 14f

    // Buttons (ui/Buttons.kt draws them; these are the title page's).
    const val BUTTON_H = 34f
    val BUTTON = TextStyle(12.5f, Weight.SEMIBOLD)
    const val BUTTON_PAD = 16f
    const val BUTTON_GAP = 10f
    const val BUTTON_ICON = 14f
    const val BUTTON_ICON_GAP = 6f
    const val CIRCLE_ICON = 16f
    const val PILL_PROGRESS_H = 2f
    const val PILL_PROGRESS_BOTTOM = 5f
    /** The fixed header block's height, and the gap under it (the rows start right under the buttons' labels). */
    const val TITLE_HEADER_H = 302f
    const val TITLE_HEADER_GAP = 0f
    /** Under the season pills, before the episode cards. */
    const val PILLS_GAP = 10f

    // Episode cards: a 16:9 still at the wide cards' size, two caption lines under it.
    const val EPISODE_ITEM_H = 138f
    const val EPISODE_CAPTION_GAP = 6f
    const val EPISODE_LINE_GAP = 2f
    val EPISODE_TITLE = TextStyle(11.5f, Weight.SEMIBOLD)
    /** "E5" in the Episodes sheet's blue, the line under it in its grey. */
    const val EPISODE_NUMBER = 0xFF8FB8FF.toInt()
    val EPISODE_META = TextStyle(11.5f, Weight.REGULAR, Ink.OWN, 0xFF9BA1A8.toInt())
    /** The ✓ watched badge. */
    const val WATCHED_MARK = "✓"
    const val NEW_BADGE = "NEW"

    /** Gliding up on a show keeps the buttons this far from the top (no cut line of text above them). */
    const val GLIDE_KEEP_TOP = 14f
    /** The backdrop: decoded at half the screen (px), the preview smaller; its crossfade. */
    const val TITLE_ART_W = 960
    const val TITLE_ART_H = 540
    const val TITLE_PREVIEW_W = 320
    const val TITLE_PREVIEW_H = 180
    const val TITLE_ART_FADE_MS = Tokens.FADE_MS
    /** The baked fade (fractions of the backdrop): clear → solid from the left, solid → clear towards the bottom. */
    const val TITLE_FADE_LEFT_FROM = 0.15f
    const val TITLE_FADE_LEFT_TO = 0.70f
    const val TITLE_FADE_BOTTOM_FROM = 0.36f
    const val TITLE_FADE_BOTTOM_TO = 0.60f
    /** The logo is used if it's ready this soon after opening; otherwise the name, for the whole visit. */
    const val LOGO_WAIT_MS = 300L
    /** The season pills' settle and the header's follow delay (the Episodes sheet's 160 ms). */
    const val SETTLE_MS = 160L
    const val MLT_CARDS = 15
    /** Pictures bound after the chosen card: episodes (4 with focus in More Like This), posters (6 on a show). */
    const val EPISODE_BIND_AFTER = 5
    const val POSTER_BIND_AFTER = 7
    /**
     * A long season's episode row (a daily show's 200) holds its cards this many at a time, and this many more once
     * focus comes within [EPISODE_AHEAD] of the last one held ([episodeWindow]).
     */
    const val EPISODE_WINDOW = 40
    const val EPISODE_AHEAD = 10

    // ---- Per row style ----

    fun cardW(style: RowStyle): Float = if (style == RowStyle.WIDE || style == RowStyle.EPISODE) WIDE_W else POSTER_W

    /** An item's height: an episode's is its still and its two caption lines. */
    fun cardH(style: RowStyle): Float = when (style) {
        RowStyle.WIDE -> WIDE_H
        RowStyle.EPISODE -> EPISODE_ITEM_H
        RowStyle.GENRES, RowStyle.SEASONS -> CHIP_H
        else -> POSTER_H
    }

    /** The picture's height in an item (an episode's still; the whole card elsewhere). */
    fun pictureH(style: RowStyle): Float = if (style == RowStyle.EPISODE) WIDE_H else cardH(style)

    /** Whether a row's items are chips laid out at their own widths. */
    fun isChips(style: RowStyle): Boolean = style == RowStyle.GENRES || style == RowStyle.SEASONS

    /** The rank number's slot left of a Top 10 poster; none elsewhere. */
    fun slot(style: RowStyle): Float = if (style == RowStyle.RANKED) POSTER_H * RANK_SLOT else 0f

    /** One item across: the slot and the card. */
    fun itemW(style: RowStyle): Float = slot(style) + cardW(style)

    /** From one item's left edge to the next one's. */
    fun pitch(style: RowStyle): Float = itemW(style) + GAP

    fun grow(style: RowStyle): Float = if (isChips(style)) GROW_CHIP else GROW

    /** How far the cards after the chosen one move along to make room for it as it grows. */
    fun extra(style: RowStyle): Float = cardW(style) * grow(style)

    /** Where an item grows from (its left edge and this height): the middle of its picture. */
    fun pivotY(style: RowStyle): Float = pictureH(style) / 2f

    /** The item's top in the strip: room for its growth above the pivot. */
    fun cardTop(style: RowStyle): Float = pivotY(style) * grow(style)

    /** The strip of cards: as tall as a grown item, which grows about [pivotY] (a card: centred in it). */
    fun stripH(style: RowStyle): Float = cardTop(style) + cardH(style) + (cardH(style) - pivotY(style)) * grow(style)
    fun stripTop(): Float = ROW_TITLE + ROW_TITLE_GAP

    /** A row's band: title, gap, strip and the gap under it. */
    fun bandH(style: RowStyle): Float = bandH(style, header = true, gapBelow = ROW_GAP)

    /** A row's band with or without its title line ([header]), and [gapBelow] under it. */
    fun bandH(style: RowStyle, header: Boolean, gapBelow: Float): Float =
        (if (header) stripTop() else 0f) + stripH(style) + gapBelow

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

    // ---- The title page's layout (the page reads real view positions; these say where they come out) ----

    /** The header block's left edge in the page: the content's x, where the rows' strips start ([PAGE_X]). */
    fun titleTextX(): Float = PAGE_X

    /** The facts row's top: under the title box. */
    fun titleFactsTop(): Float = TITLE_TOP + TITLE_BOX_H + FACTS_GAP

    /** The story's top and height: three 13 sp lines with their 2 dp gaps (Figtree's line is 1.2 x its size). */
    fun titleStoryTop(): Float = titleFactsTop() + FACTS_ROW_H + STORY_GAP
    fun titleStoryH(): Float = STORY_LINES * (STORY.lineSp + STORY.lineGap)

    fun titleCastTop(): Float = titleStoryTop() + titleStoryH() + CAST_GAP
    fun titleSourceTop(): Float = titleCastTop() + CAST.lineSp + SOURCE_GAP
    fun titleButtonsTop(): Float = titleSourceTop() + SOURCE.lineSp + BUTTONS_GAP_ABOVE

    /**
     * How many of a season's [total] episode cards the row holds with card [at] chosen and [have] held now: never fewer
     * than [have], at least [EPISODE_WINDOW], and [EPISODE_WINDOW] more while [at] is within [EPISODE_AHEAD] of the end
     * (so a held D-pad adds cards in steps, not one per press); never more than [total].
     */
    fun episodeWindow(total: Int, at: Int, have: Int): Int {
        var n = maxOf(have, EPISODE_WINDOW)
        while (at + EPISODE_AHEAD >= n && n < total) n += EPISODE_WINDOW
        return minOf(n, total).coerceAtLeast(0)
    }

    /** A show's rows under the header: the season pills' band (when there are pills), the episodes', More Like This'. */
    fun pillsBandH(): Float = bandH(RowStyle.SEASONS, header = false, gapBelow = PILLS_GAP)
    fun episodesBandH(): Float = bandH(RowStyle.EPISODE, header = false, gapBelow = ROW_GAP)

    /**
     * How far a show's page glides up for More Like This: enough for its band to end on screen, and never less than
     * keeping the buttons [GLIDE_KEEP_TOP] from the top (all the text above them leaves the screen).
     */
    fun glide(moreBottom: Float, buttonsTop: Float, pageH: Float = SCREEN_H, keepTop: Float = GLIDE_KEEP_TOP): Float =
        maxOf(moreBottom - pageH, buttonsTop - keepTop, 0f)
}
