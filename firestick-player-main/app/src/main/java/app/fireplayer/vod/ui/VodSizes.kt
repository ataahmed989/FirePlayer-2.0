package app.fireplayer.vod.ui

import app.fireplayer.shell.NavRail
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.TextStyle.Ink
import app.fireplayer.shell.TextStyle.Weight
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.PageRow
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
    /** My List: its header holds still, and its rows scroll under it from here to the screen's bottom. */
    fun fixedRowsTop(): Float = VIEWPORT_TOP + HEADER_H + HEADER_GAP

    // The Search tab: its bar (the Settings tab's row, [PageRow]) where the header would be, the round trash button
    // beside it (the title page's round buttons, at the bar's height), the history list under it (Settings rows too),
    // and the wait after the last key before the results are asked for.
    const val SEARCH_BAR_H = PageRow.ROW_DP
    const val SEARCH_TRASH = PageRow.ROW_DP
    const val SEARCH_TRASH_ICON = 18f
    const val SEARCH_TRASH_GAP = 12f
    /** Room under the trash button for its name, which shows while it is focused. */
    const val SEARCH_TRASH_LABEL = 22f
    /** Between the bar and what's under it (the history list, the first row of results). */
    const val SEARCH_GAP = HEADER_GAP
    /** The history list's room at the screen's bottom. */
    const val SEARCH_LIST_BOTTOM = 16f
    const val SEARCH_WAIT_MS = 500L
    /** The bar's hint words: the row's own text colour (light, or dark on the white focused row) at this alpha. */
    const val SEARCH_HINT_ALPHA = 0x8C
    fun searchBarW(): Float = tabHeaderW() - SEARCH_TRASH_GAP - SEARCH_TRASH
    fun searchListTop(): Float = VIEWPORT_TOP + SEARCH_BAR_H + SEARCH_GAP

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
    /** A page's short line where its rows would be (My List with nothing in it, Search with no history yet). */
    val NOTE = TextStyle(15f, Weight.REGULAR, Ink.MUTED)
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

    // ---- The title page (Design-References/redesign-2026-11-19: concept 3 without its Episodes button) ----

    /** The text block: the title box, the facts row and its callout, the story, the source line (fixed, so words only redraw). */
    const val TITLE_TOP = 30f
    const val TITLE_BOX_W = 560f
    const val TITLE_BOX_H = 84f
    /** The logo fits inside this box (left and bottom aligned in the title box); decoded at most this size, px. */
    const val LOGO_W = 440f
    const val LOGO_DECODE_W = 800
    const val LOGO_DECODE_H = 180
    val TITLE_NAME = TextStyle(40f, Weight.BOLD)
    /** A name too wide for the title box at 40 sp: one line at 28 sp, cut short. */
    val TITLE_NAME_LONG = TextStyle(28f, Weight.BOLD)
    const val FACTS_GAP = 8f
    const val FACTS_ROW_H = 22f
    /** The facts and the story: the page headers' styles ([FACTS], [STORY]), a size up. */
    val TITLE_FACTS = FACTS.copy(sp = 14f)
    val TITLE_STORY = STORY.copy(sp = 14f)
    val CALLOUT = TextStyle(11.5f, Weight.SEMIBOLD)
    const val CALLOUT_PAD = 9f
    const val CALLOUT_GAP = 10f
    const val STORY_GAP = 6f
    const val STORY_LINES = 2
    const val TITLE_TEXT_W = 520f
    const val SOURCE_GAP = 6f
    val SOURCE = TextStyle(12f, Weight.REGULAR, Ink.MUTED)
    /** The text block's height (its lines, and 8 dp under the source line), and the gap under it. */
    const val TITLE_HEADER_H = 216f
    const val TITLE_HEADER_GAP = 0f

    // The stage under the text block: the action column on the left; right of it a show's season dropdown, the
    // "Episodes" heading and the episode pods, or a film's More Like This.
    /** The action column: Play / Resume, Play from beginning and Sources (pills as wide as it), then (+) and (⋯). */
    const val ACTIONS_W = 216f
    const val BUTTON_H = 42f
    val BUTTON = TextStyle(13f, Weight.SEMIBOLD)
    /** A pill's words when they'd be cut short at that size ("Resume S12 E15 · 1h 12m left", the wait for the add-ons). */
    val BUTTON_LONG = TextStyle(11.5f, Weight.SEMIBOLD)
    const val BUTTON_PAD = 14f
    const val BUTTON_GAP = 12f
    const val BUTTON_ICON = 15f
    const val BUTTON_ICON_GAP = 7f
    const val CIRCLE_ICON = 18f
    const val PILL_PROGRESS_H = 2f
    const val PILL_PROGRESS_BOTTOM = 6f
    /** The circles' names hang under them while one is chosen: their room under the column. */
    const val CIRCLE_NAME_ROOM = 20f
    /** From the column to what's right of it. */
    const val STAGE_GAP = 24f
    /** The first button's top in the stage; the pods (and a film's posters) start level with it. */
    const val STAGE_ROW_TOP = 56f
    /** The season dropdown and the heading beside it: their top in the stage, their height, the gap between them. */
    const val SEASON_TOP = 6f
    const val SEASON_H = 34f
    val SEASON = TextStyle(13.5f, Weight.SEMIBOLD)
    const val SEASON_PAD = 16f
    const val SEASON_ICON = 14f
    const val SEASON_ICON_GAP = 8f
    const val SEASON_HEADING_GAP = 14f
    /** "Episodes": a heading (never a button), the row titles' colour. */
    val EPISODES_HEADING = ROW_TITLE_STYLE.copy(sp = 19f)

    // The season dropdown's list: a small glass window under the dropdown; more seasons than fit scroll inside it.
    const val SEASON_LIST_W = 176f
    const val SEASON_LIST_PAD = 8f
    const val SEASON_LIST_ROW_H = 34f
    const val SEASON_LIST_ROW_GAP = 4f
    const val SEASON_LIST_ROWS = 6
    const val SEASON_LIST_GAP = 6f
    const val SEASON_LIST_INSET = 12f
    const val SEASON_LIST_MS = 120L

    // Episode pods: a 16:9 still over an information base ("E1 · A Shadow of the Past", "1h 7m · Sep 1, 2022"), about
    // two and part of a third across beside the column; the chosen one grows a little, with the cards' white ring.
    const val POD_W = 264f
    const val POD_PICTURE_H = 148f
    const val POD_BASE_H = 52f
    const val POD_GAP = GAP
    const val POD_GROW = 0.06f
    const val POD_TEXT_SIDE = 12f
    const val POD_TEXT_TOP = 9f
    const val POD_LINE_GAP = 3f
    val POD_TITLE = TextStyle(13.5f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val POD_META = TextStyle(12f, Weight.REGULAR, Ink.OWN, 0xFFA9B1BA.toInt())
    /** Room above and below the pods for the chosen one's growth and its ring. */
    const val POD_ROOM = 10f
    /**
     * Under that room, before a show's More Like This: its title then shows above the screen's bottom (a hint) while
     * its posters start just below it (so they hold no pictures until the page glides up).
     */
    const val STAGE_BOTTOM = 22f
    /** Pictures bound after the chosen pod (one fewer with focus in More Like This); posters (6 on a show). */
    const val POD_BIND_AFTER = 3
    const val POSTER_BIND_AFTER = 7

    // Episode cards as the rows draw them (CardView's EPISODE kind): a 16:9 still, two caption lines under it.
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

    /** Gliding up on a show keeps the season dropdown's row this far from the top (no cut line of text above it). */
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
    /** The header's follow delay once focus rests on an episode (the Episodes sheet's 160 ms). */
    const val SETTLE_MS = 160L
    const val MLT_CARDS = 15
    /**
     * A long season's episode row (a daily show's 200) holds its pods this many at a time, and this many more once
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

    /** The text block's and the action column's left edge in the page: the content's x ([PAGE_X]). */
    fun titleTextX(): Float = PAGE_X

    /** The facts row's top: under the title box. */
    fun titleFactsTop(): Float = TITLE_TOP + TITLE_BOX_H + FACTS_GAP

    /** The story's top and height: two 14 sp lines with their 2 dp gaps (Figtree's line is 1.2 x its size). */
    fun titleStoryTop(): Float = titleFactsTop() + FACTS_ROW_H + STORY_GAP
    fun titleStoryH(): Float = STORY_LINES * (TITLE_STORY.lineSp + TITLE_STORY.lineGap)

    fun titleSourceTop(): Float = titleStoryTop() + titleStoryH() + SOURCE_GAP

    /** Where the stage's right part starts in the page (pods, the season dropdown, a film's posters). */
    fun stageX(): Float = PAGE_X + ACTIONS_W + STAGE_GAP

    /** The room for a pill's words in the column: between its padding, after its icon when it has one. */
    fun pillTextW(icon: Boolean): Float = ACTIONS_W - 2 * BUTTON_PAD - if (icon) BUTTON_ICON + BUTTON_ICON_GAP else 0f

    /** The action column with all it can hold: three pills and the circles, [BUTTON_GAP] apart. */
    fun actionsH(): Float = 4 * BUTTON_H + 3 * BUTTON_GAP

    /** A pod: its still and its base; from one pod's left edge to the next one's; how far the next ones make room. */
    fun podH(): Float = POD_PICTURE_H + POD_BASE_H
    fun podPitch(): Float = POD_W + POD_GAP
    fun podExtra(): Float = POD_W * POD_GROW

    /** The pods' band in the stage: the pods and [POD_ROOM] above and below them. */
    fun podsBandH(): Float = podH() + 2 * POD_ROOM

    /** A show's stage: down to the room under the pods, then [STAGE_BOTTOM] (More Like This starts under it). */
    fun showStageH(): Float = STAGE_ROW_TOP + podH() + POD_ROOM + STAGE_BOTTOM

    /** A film's More Like This in the stage: its band's top, so its posters start level with the first button. */
    fun filmMoreTop(): Float = (STAGE_ROW_TOP - stripTop() - cardTop(RowStyle.POSTER)).coerceAtLeast(0f)

    /** A film's stage: its More Like This band, or the whole action column and the circles' names if that's taller. */
    fun filmStageH(): Float = maxOf(filmMoreTop() + bandH(RowStyle.POSTER), STAGE_ROW_TOP + actionsH() + CIRCLE_NAME_ROOM)

    /** The season list's height with [seasons] in it: at most [SEASON_LIST_ROWS] rows show, the rest scroll. */
    fun seasonListH(seasons: Int): Float {
        val rows = seasons.coerceIn(1, SEASON_LIST_ROWS)
        return 2 * SEASON_LIST_PAD + rows * SEASON_LIST_ROW_H + (rows - 1) * SEASON_LIST_ROW_GAP
    }

    /**
     * The first row showing in the season list with row [at] chosen, [first] showing first now and [count] rows in
     * all: it moves only as far as [at] needs to be inside the [SEASON_LIST_ROWS] that show.
     */
    fun seasonListFirst(at: Int, first: Int, count: Int): Int {
        val last = (count - SEASON_LIST_ROWS).coerceAtLeast(0)
        val f = when {
            at < first -> at
            at >= first + SEASON_LIST_ROWS -> at - SEASON_LIST_ROWS + 1
            else -> first
        }
        return f.coerceIn(0, last)
    }

    /**
     * How many of a season's [total] episode pods the row holds with pod [at] chosen and [have] held now: never fewer
     * than [have], at least [EPISODE_WINDOW], and [EPISODE_WINDOW] more while [at] is within [EPISODE_AHEAD] of the end
     * (so a held D-pad adds pods in steps, not one per press); never more than [total].
     */
    fun episodeWindow(total: Int, at: Int, have: Int): Int {
        var n = maxOf(have, EPISODE_WINDOW)
        while (at + EPISODE_AHEAD >= n && n < total) n += EPISODE_WINDOW
        return minOf(n, total).coerceAtLeast(0)
    }

    /**
     * How far a show's page glides up for More Like This: enough for its band to end on screen, and never less than
     * keeping the season dropdown's row ([rowTop]) [GLIDE_KEEP_TOP] from the top (all the text above it leaves the
     * screen).
     */
    fun glide(moreBottom: Float, rowTop: Float, pageH: Float = SCREEN_H, keepTop: Float = GLIDE_KEEP_TOP): Float =
        maxOf(moreBottom - pageH, rowTop - keepTop, 0f)
}
