package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.live.ui.HoldPopup
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.Spring
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Season
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.data.Callouts
import app.fireplayer.vod.data.VodArt
import app.fireplayer.vod.hasImdbId
import app.fireplayer.vod.playTarget
import app.fireplayer.vod.ui.TitleRules.Part
import java.util.Calendar

/**
 * One title's page (design-brief.md §6.4, layout A), full screen over its tab beside the rail's strip: the backdrop at
 * the top right behind everything ([HeaderArt], never moving), the header block ([TitleHeader]: title or logo, facts
 * and one callout, story, cast line, the reserved source line and the buttons), then its rows, built from the tab
 * pages' own parts ([TitleRow], [CardView], [app.fireplayer.ui.RowMotion], [PageColumn]): a show's season pills and
 * episode cards, and More Like This. It draws first from what the poster's [Title] says, then from the repository's
 * details as they come.
 *
 * Moving: the buttons Left/Right; Down to the Play target's episode card (a show) or More Like This (a film); season
 * pills change the season once focus rests on one; a show's page glides up for More Like This and back. Hold OK on an
 * episode card or a poster opens the shared small window ([HoldWindow]); (⋯) opens it with Mark watched, Remove from
 * Continue Watching and Details (a [SidePanel] window). Playback and sources are the next step: they say so.
 *
 * Lean: the backdrop is decoded once at half the screen with its fade baked in, stills at their shown size, posters as
 * the rows'; only translations and scales change while anything moves; pictures, loads and timers stop when the page is
 * covered or closed.
 */
class TitlePage(private val ctx: Context, private val t: Tokens, private val host: Host, private val opened: Title) :
    TitleRow.Listener, TitleButtons.Listener {

    interface Host {
        val repo: VodRepository?
        val hold: HoldWindow
        val sidePanel: SidePanel
        fun showNotice(text: String)
        fun focusRail()
        /** OK on a More Like This poster (its IMDb id known): that title's page, on top of this one. */
        fun openNext(title: Title)
    }

    /** Where focus was in the page, to come back to it. */
    internal data class Saved(
        val part: Part,
        val button: TitleButtons.Which,
        val season: Int?,
        val episodeId: String?,
        val episodeIndex: Int,
        val moreId: String?,
        val moreIndex: Int,
    )

    private val isShow = opened.kind == Kind.SERIES
    private val main = Handler(Looper.getMainLooper())

    /** Page-sized; clips at the page's left edge (the sliver of the card before the chosen one shows, as in the rows). */
    val root = FrameLayout(ctx).apply { clipChildren = true }
    private val backdrop = HeaderArt(ctx, HeaderArt.ArtSpec.TITLE)
    private val column = PageColumn(ctx, t, VodSizes.TITLE_HEADER_H, VodSizes.TITLE_HEADER_GAP)
    private val header = TitleHeader(ctx, t, this)
    private val pills = TitleRow(ctx, t, spec("seasons", "Seasons", RowStyle.SEASONS), this, header = false, gapBelow = VodSizes.PILLS_GAP)
    private val episodes = TitleRow(ctx, t, spec("episodes", "Episodes", RowStyle.EPISODE), this, header = false)
    private val more = TitleRow(ctx, t, spec("more", "More Like This", RowStyle.POSTER), this)
    /** The column's upward offset, px: a show's page glides up for More Like This. */
    private val glide = Spring(VodSizes.PAGE_OMEGA, VodSizes.PAGE_SETTLE) { column.translationY = -it }
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    private var details = TitleDetails.of(opened)
    private var seasons: List<Season> = emptyList()
    /** The season whose cards show, and the one a pill is resting on (shown after [VodSizes.SETTLE_MS]). */
    private var shownSeason: Int? = null
    private var cardsSeason: Int? = null
    private var pendingSeason: Int? = null
    private var shownEpisodes: List<Episode> = emptyList()
    private var target: PlayTarget? = null
    private var pendingMore: List<Card>? = null
    /** Where focus is (or was last) in the page. */
    private var part = Part.BUTTONS
    /** Focus to bring back once the rows it was in have their cards again (a page made again). */
    private var restore: Saved? = null
    private var restoreHolder: View? = null

    private var detailsCancel: Cancel? = null
    private var moreCancel: Cancel? = null
    private var resolveCancel: Cancel? = null
    private var detailsFresh = false
    private var moreFresh = false
    private var paused = false
    private var released = false

    /** The title box: waiting (at most [VodSizes.LOGO_WAIT_MS]) for the logo, then the logo or the name for the visit. */
    private enum class TitleMode { WAITING, LOGO, NAME }
    private var mode = TitleMode.WAITING
    private var logoUrl: String? = null
    private var backdropUrl: String? = null
    /** The story stays put once shown past the first moments (TMDB's arriving later doesn't swap it). */
    private var storyLocked = false

    init {
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.header = header
        column.onRowsMoved = { onRowsMoved() }
        for (row in listOf(pills, episodes, more)) {
            column.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.bandHeight))
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The backdrop covers the whole screen: its part under the rail's strip lies left of the page.
        backdrop.addOnLayoutChangeListener { v, l, tp, r, b, _, _, _, _ ->
            backdrop.frame = RectF(-t.dpf(VodSizes.RAIL), 0f, (r - l).toFloat(), (b - tp).toFloat())
            if (v.width > 0) v.invalidate()
        }
        // Season pills that fit stay put; they move only as far as the last one needs.
        pills.strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pills.clampToView(root.width - t.dp(VodSizes.PAGE_X) - t.dp(VodSizes.RIGHT_MARGIN))
        }
        header.logo.onArrived = { onLogo() }
    }

    private fun spec(key: String, label: String, style: RowStyle) = RowSpec("title.$key", label, style, Source.Fixed("title.$key"))

    // ---- Lifecycle --------------------------------------------------------------------------------------------------

    /** Shown for the first time: what the poster says at once, then the details, More Like This and pictures. */
    fun start() {
        refreshAll()
        startLoads()
        startArt(opened.background)
        requestLogo(opened.logo)
        main.postDelayed(decideTitle, VodSizes.LOGO_WAIT_MS)
    }

    /** Covered by another page ([hide]), or the screen stopped: loads, timers and pictures stop. */
    fun pause(hide: Boolean) {
        if (hide) root.visibility = View.GONE
        if (paused) return
        paused = true
        stopLoads()
        main.removeCallbacksAndMessages(null)
        // The moment for the logo has passed.
        if (mode == TitleMode.WAITING) decide(TitleMode.NAME)
        storyLocked = true
        pendingSeason?.let { applySeason(it) }
        if (glide.moving) glide.snap(glide.target)
        for (row in rows()) {
            row.motion.settle()
            row.releaseImages()
        }
        press.cancel()
        backdrop.release()
        ImageLoader.release(header.logo)
    }

    /** Shown again ([show]: back on top of the stack), or the screen started: pictures back, loads finished. */
    fun resume(show: Boolean) {
        if (show) root.visibility = View.VISIBLE
        if (!paused || released) return
        paused = false
        if (!detailsFresh || !moreFresh) startLoads()
        backdropUrl?.let { backdrop.show(it) }
        if (mode == TitleMode.LOGO) logoUrl?.let { loadLogo(it) }
        refreshAll()
        updateImages()
    }

    /** Gone for good: loads stop, pictures go, and the big ones leave memory too. */
    fun release() {
        if (released) return
        pause(hide = true)
        released = true
        glide.stop()
        backdropUrl?.let { url ->
            ImageLoader.drop(backdrop.keyFor(url))
            VodArt.backgroundAt(url, VodArt.Size.SMALL)?.let { small ->
                ImageLoader.drop(ImageLoader.keyOf(small, VodSizes.TITLE_PREVIEW_W, VodSizes.TITLE_PREVIEW_H, HeaderArt.ArtSpec.TITLE_PREVIEW.keySuffix))
            }
        }
        logoUrl?.let { ImageLoader.drop(logoKey(it)) }
    }

    fun applyLook() {
        header.applyLook()
        for (row in rows()) row.applyLook()
    }

    /** Memory is short: only the backdrop and the row focus is in keep their pictures. */
    fun trim() {
        val keep = rowOf(part)
        for (row in rows()) if (row !== keep) row.releaseImages()
    }

    fun onLibraryChanged() {
        if (paused || released) return
        refreshAll()
    }

    private fun rows() = listOf(pills, episodes, more)

    private fun startLoads() {
        val r = host.repo ?: return
        stopLoads()
        if (!detailsFresh) {
            detailsCancel = r.details(opened) { d, fresh -> if (!released) onDetails(d, fresh) }
        }
        if (!moreFresh) {
            moreCancel = r.moreLikeThis(opened) { cards, fresh -> if (!released) onMore(cards, fresh) }
        }
    }

    private fun stopLoads() {
        detailsCancel?.cancel()
        detailsCancel = null
        moreCancel?.cancel()
        moreCancel = null
        resolveCancel?.cancel()
        resolveCancel = null
    }

    // ---- Data -------------------------------------------------------------------------------------------------------

    private fun onDetails(d: TitleDetails, fresh: Boolean) {
        // The page is about the title it was opened for: its id and kind stay.
        details = if (d.title.id == opened.id && d.title.kind == opened.kind) d else d.copy(title = d.title.copy(id = opened.id, kind = opened.kind))
        if (fresh) detailsFresh = true
        startArt(details.title.background)
        if (mode == TitleMode.WAITING) {
            requestLogo(details.title.logo)
            // The last answer has no logo: no need to wait for one.
            if (fresh && logoUrl == null) decide(TitleMode.NAME)
        } else if (logoUrl == null) {
            // Known too late for this visit: fetched to disk, so the next visit can show it.
            details.title.logo?.let {
                logoUrl = it
                ImageLoader.prefetch(it)
            }
        }
        seasons = TitleRules.seasonOrder(details.seasons)
        refreshAll()
        applyRestore()
    }

    private fun onMore(cards: List<Card>, fresh: Boolean) {
        if (fresh) moreFresh = true
        val list = cards.filter { it.title.id != opened.id }.distinctBy { it.title.id }.take(VodSizes.MLT_CARDS)
        // An empty stored answer: the fresh one follows.
        if (list.isEmpty() && !fresh) return
        // The viewer is in the row and the new cards would move what they're on: held until they leave it.
        if (!RowRules.keepsFocus(more.focusedIndex(), more.cards, false, list, false)) {
            pendingMore = list
            return
        }
        showMore(list)
        applyRestore()
    }

    private fun showMore(list: List<Card>) {
        pendingMore = null
        if (list.isEmpty()) {
            if (more.focusedIndex() >= 0) moveUpFrom(Part.MORE)
            more.releaseImages()
            more.visibility = View.GONE
            more.bind(emptyList(), false)
            return
        }
        val f = RowRules.focusAfter(more.focusedIndex(), more.cards.size, false, list.size, false)
        if (f >= 0) more.items.getOrNull(f)?.requestFocus()
        more.bind(list, false)
        if (more.visibility != View.VISIBLE) {
            more.chosen = 0
            more.motion.snap(0, isGrown = false)
            more.visibility = View.VISIBLE
        }
        updateImages()
    }

    /** Everything that follows the details and the library: the buttons, the header's words, the seasons and cards. */
    private fun refreshAll() {
        val r = host.repo
        val now = System.currentTimeMillis()
        val year = thisYear()
        val last = r?.lastPlayed(opened)
        val play: TitleRules.Play
        var filmWatched = false
        val out: Boolean
        if (isShow) {
            target = playTarget(details.episodes, last, now)
            play = TitleRules.showPlay(details, target, year)
            out = details.episodes.isEmpty() || target != null
        } else {
            target = null
            play = TitleRules.filmPlay(details, last, now, year)
            filmWatched = r?.isWatched(opened.id) == true
            out = play.enabled
        }
        val buttons = header.buttons
        buttons.setPlay(play)
        buttons.setRestart(play.restart)
        buttons.setSources(out)
        buttons.setMyList(r?.inMyList(opened.id) == true)
        // The repository's rule for the one chip (the same one its tests check against the research's fixtures).
        val callout = Callouts.choose(details, filmWatched, { r?.isEpisodeWatched(it) == true }, target, now)
        val storyNow = header.storyShown
        val story = if (storyLocked && storyNow.isNotEmpty()) storyNow else TitleRules.story(details)
        header.show(TitleRules.facts(details), callout?.let { TitleRules.calloutText(it, year) }, story, TitleRules.castLine(details))
        if (mode == TitleMode.NAME) header.showName(details.title.name)
        if (isShow) refreshSeasons()
        buttons.setEpisodes(isShow && episodes.visibility == View.VISIBLE)
    }

    private fun refreshSeasons() {
        var place = false
        if (TitleRules.showsPills(seasons)) {
            pills.bindChips(seasons.map { TitleRules.seasonLabel(it.number) })
            if (pills.visibility != View.VISIBLE) {
                pills.visibility = View.VISIBLE
                place = true
            }
        } else if (pills.visibility == View.VISIBLE) {
            if (pills.focusedIndex() >= 0) header.buttons.focusLast()
            pills.visibility = View.GONE
            pills.bindChips(emptyList())
        }
        if (shownSeason == null || seasons.none { it.number == shownSeason }) {
            // The page opens on the Play target's season.
            shownSeason = TitleRules.openingSeason(seasons, target?.episode)
            place = true
        }
        val i = seasonIndex(shownSeason)
        if (place && i >= 0 && pills.focusedIndex() < 0) {
            pills.chosen = i
            pills.motion.snap(i, isGrown = false)
        }
        pills.markSelected(i)
        showSeasonCards()
    }

    private fun seasonIndex(n: Int?): Int = seasons.indexOfFirst { it.number == n }

    /** The pills' strip moved straight to season [n]'s pill (not focused): a season shown from elsewhere. */
    private fun placePills(n: Int) {
        val i = seasonIndex(n)
        if (i < 0 || pills.focusedIndex() >= 0) return
        pills.chosen = i
        pills.motion.snap(i, isGrown = false)
    }

    /** The shown season's episode cards, filled in place (a new season starts at its first card). */
    private fun showSeasonCards() {
        val r = host.repo
        val season = shownSeason
        val eps = seasons.firstOrNull { it.number == season }?.episodes.orEmpty()
        if (eps.isEmpty()) {
            if (episodes.visibility == View.VISIBLE) {
                if (episodes.focusedIndex() >= 0) header.buttons.focusLast()
                episodes.releaseImages()
                episodes.visibility = View.GONE
                episodes.bind(emptyList(), false)
            }
            shownEpisodes = emptyList()
            cardsSeason = null
            return
        }
        val now = System.currentTimeMillis()
        val year = thisYear()
        val cards = eps.map { ep ->
            TitleRules.episodeCard(details.title, ep, r?.isEpisodeWatched(ep) == true, r?.positionOf(ep), now, year)
        }
        val newSeason = cardsSeason != season
        shownEpisodes = eps
        cardsSeason = season
        if (newSeason) {
            val inRow = episodes.focusedIndex() >= 0
            if (inRow) {
                // Focus on a card that stays (the first) before any go.
                episodes.items.getOrNull(0)?.requestFocus()
                episodes.chosen = 0
            } else {
                // At rest the row starts at the Play target when it's in this season (Down then only grows it), else E1.
                episodes.chosen = TitleRules.focusIndex(eps, target?.episode)
            }
            episodes.motion.snap(episodes.chosen, isGrown = inRow)
        } else {
            val f = RowRules.focusAfter(episodes.focusedIndex(), episodes.cards.size, false, cards.size, false)
            if (f >= 0) episodes.items.getOrNull(f)?.requestFocus()
        }
        episodes.bind(cards, false)
        if (episodes.visibility != View.VISIBLE) {
            episodes.motion.snap(episodes.chosen, isGrown = false)
            episodes.visibility = View.VISIBLE
        }
        // The header follows the chosen card's new words.
        if (episodes.focusedIndex() >= 0) main.post(follow)
        updateImages()
    }

    // ---- Art and the title box -------------------------------------------------------------------------------------

    /**
     * The backdrop, decided once ([url] the first known): never swapped mid-visit. Shown at once from memory when it's
     * there; else a smaller copy first (this page's preview, or the page header's crop of it), crossfaded over.
     */
    private fun startArt(url: String?) {
        if (backdropUrl != null || url == null) return
        backdropUrl = url
        if (ImageLoader.cached(backdrop.keyFor(url)) == null) {
            val small = VodArt.backgroundAt(url, VodArt.Size.SMALL)
            val preview = HeaderArt.ArtSpec.TITLE_PREVIEW
            val held = small?.let { ImageLoader.cached(ImageLoader.keyOf(it, preview.decodeW, preview.decodeH, preview.keySuffix)) }
            if (held != null) {
                backdrop.hold(held, cover = false)
            } else {
                headerCrop(url)?.let { backdrop.hold(it, cover = true) } ?: backdrop.preview(small, preview)
            }
        }
        backdrop.show(url)
    }

    /** The bitmap a page header (Home's, or Movies' and TV Shows') already decoded for [url], if it's in memory. */
    private fun headerCrop(url: String) = listOf(VodSizes.homeHeaderW(), VodSizes.tabHeaderW()).firstNotNullOfOrNull { cardW ->
        val inset = t.dp(VodSizes.HEADER_ART_INSET)
        val w = (t.dp(VodSizes.backdropW(cardW)) - inset) / 2
        val h = (t.dp(VodSizes.HEADER_H) - 2 * inset) / 2
        ImageLoader.cached(ImageLoader.keyOf(url, w.coerceAtLeast(1), h.coerceAtLeast(1), HeaderArt.ArtSpec.HEADER.keySuffix))
    }

    private fun logoKey(url: String) =
        ImageLoader.keyOf(url, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H, "|in")

    /** The logo, if the title has one, while the title box still waits: used at once when it's in memory. */
    private fun requestLogo(url: String?) {
        if (mode != TitleMode.WAITING || url == null || logoUrl != null) return
        logoUrl = url
        loadLogo(url)
    }

    private fun loadLogo(url: String) {
        ImageLoader.load(
            url, header.logo, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H, android.graphics.Bitmap.Config.ARGB_8888,
            fit = ImageLoader.Fit.INSIDE,
        )
    }

    private fun onLogo() {
        if (mode == TitleMode.WAITING) decide(TitleMode.LOGO)
    }

    private val decideTitle = Runnable {
        storyLocked = true
        if (mode == TitleMode.WAITING) decide(TitleMode.NAME)
    }

    /**
     * The title box's choice for this visit: the logo, or the name. A logo still on its way then isn't decoded, but its
     * bytes still reach the disk, for the next visit.
     */
    private fun decide(m: TitleMode) {
        mode = m
        if (m == TitleMode.LOGO) {
            header.showLogo(details.title.name)
        } else {
            ImageLoader.release(header.logo)
            header.showName(details.title.name)
        }
    }

    // ---- Focus ------------------------------------------------------------------------------------------------------

    /** Into the page as it opens: Play, or the first button that can take focus. */
    fun takeFocus() {
        part = Part.BUTTONS
        if (!header.buttons.focusFirst()) root.requestFocus()
    }

    /** Back into the page (from the rail): where focus was. */
    fun takeFocusBack() {
        val ok = when (part) {
            Part.BUTTONS -> header.buttons.focusLast()
            else -> rowOf(part)?.takeIf { it.visibility == View.VISIBLE }?.focusChosen() == true
        }
        if (!ok) takeFocus()
    }

    /** Whether focus is in one of the rows (Back then goes to the buttons). */
    fun focusInRows(): Boolean = rows().any { it.visibility == View.VISIBLE && it.focusedIndex() >= 0 }

    /** Back from a row: focus on Play (or the first button), the page at its top. */
    fun backToButtons() {
        header.buttons.focusFirst()
        glide.glideTo(0f)
    }

    internal fun saveFocus(): Saved = Saved(
        part = part,
        button = header.buttons.last,
        season = shownSeason,
        episodeId = episodes.cards.getOrNull(episodes.chosen)?.title?.id,
        episodeIndex = episodes.chosen,
        moreId = more.cards.getOrNull(more.chosen)?.title?.id,
        moreIndex = more.chosen,
    )

    /** Focus where [saved] says (a page come back to); rows not filled yet take it when their cards come. */
    internal fun restoreFocus(saved: Saved?) {
        if (saved == null) {
            takeFocus()
            return
        }
        if (!tryRestore(saved)) {
            header.buttons.focus(saved.button)
            restore = saved
            restoreHolder = root.findFocus()
        }
        // A row kept grown while the page was covered, and focus isn't back in it: its card shrinks.
        for (row in rows()) if (row.focusedIndex() < 0) row.motion.shrink()
    }

    private fun applyRestore() {
        val s = restore ?: return
        // Only while focus is still where it was put to wait.
        if (root.findFocus() !== restoreHolder) {
            restore = null
            return
        }
        if (tryRestore(s)) restore = null
    }

    private fun tryRestore(s: Saved): Boolean = when (s.part) {
        Part.BUTTONS -> header.buttons.focus(s.button)
        Part.PILLS -> {
            val i = seasonIndex(s.season)
            if (pills.visibility != View.VISIBLE || i < 0) false else {
                // The season it was on shows at once (not after the pills' settle).
                s.season?.let { if (it != shownSeason) applySeason(it) }
                pills.chosen = i
                pills.motion.snap(i, isGrown = true)
                pills.focusChosen()
            }
        }
        Part.EPISODES -> {
            if (s.season != null && s.season != shownSeason && seasonIndex(s.season) >= 0) {
                applySeason(s.season)
                placePills(s.season)
            }
            val i = episodes.cards.indexOfFirst { it.title.id == s.episodeId }.takeIf { it >= 0 } ?: s.episodeIndex
            if (episodes.visibility != View.VISIBLE || episodes.items.isEmpty()) false else {
                episodes.chosen = i.coerceIn(0, episodes.items.size - 1)
                episodes.motion.snap(episodes.chosen, isGrown = true)
                episodes.focusChosen()
            }
        }
        Part.MORE -> {
            val i = more.cards.indexOfFirst { it.title.id == s.moreId }.takeIf { it >= 0 } ?: s.moreIndex
            if (more.visibility != View.VISIBLE || more.items.isEmpty()) false else {
                more.chosen = i.coerceIn(0, more.items.size - 1)
                more.motion.snap(more.chosen, isGrown = true)
                val ok = more.focusChosen()
                // Straight to where it was, the page glided up.
                if (ok && isShow) column.post { glide.snap(glideTarget()) }
                ok
            }
        }
    }

    private fun rowOf(p: Part): TitleRow? = when (p) {
        Part.PILLS -> pills
        Part.EPISODES -> episodes
        Part.MORE -> more
        Part.BUTTONS -> null
    }

    private fun partOf(row: TitleRow): Part = when (row) {
        pills -> Part.PILLS
        episodes -> Part.EPISODES
        else -> Part.MORE
    }

    private fun parts() = TitleRules.Parts(
        pills = pills.visibility == View.VISIBLE && pills.items.isNotEmpty(),
        episodes = episodes.visibility == View.VISIBLE && episodes.items.isNotEmpty(),
        more = more.visibility == View.VISIBLE && more.items.isNotEmpty(),
    )

    /** Focus into [to], as the D-pad map says: the episode cards on the Play target (from the buttons) or E1. */
    private fun focusPart(to: Part, from: Part) {
        when (to) {
            Part.BUTTONS -> header.buttons.focusLast()
            Part.PILLS -> {
                pills.chosen = seasonIndex(shownSeason).coerceAtLeast(0)
                pills.focusChosen()
            }
            Part.EPISODES -> when (from) {
                Part.BUTTONS -> focusEpisodes(toTarget = true)
                Part.PILLS -> focusEpisodes(toTarget = false)
                else -> episodes.focusChosen()
            }
            Part.MORE -> more.focusChosen()
        }
    }

    /**
     * Into the episode cards: from the buttons, the Play target's card (its season shown first); from the pills, the
     * Play target if it's in the season shown, else its first card.
     */
    private fun focusEpisodes(toTarget: Boolean) {
        pendingSeason?.let { applySeason(it) }
        val tgt = target?.episode
        if (toTarget && tgt != null && tgt.season != shownSeason && seasonIndex(tgt.season) >= 0) {
            applySeason(tgt.season)
            placePills(tgt.season)
        }
        if (episodes.items.isEmpty()) return
        episodes.chosen = TitleRules.focusIndex(shownEpisodes, tgt)
        episodes.focusChosen()
    }

    private fun moveDownFrom(from: Part) {
        TitleRules.down(from, parts())?.let { focusPart(it, from) }
    }

    private fun moveUpFrom(from: Part) {
        val to = TitleRules.up(from, parts()) ?: return
        focusPart(to, from)
    }

    // ---- Buttons ----------------------------------------------------------------------------------------------------

    override fun onButton(which: TitleButtons.Which) {
        when (which) {
            TitleButtons.Which.PLAY, TitleButtons.Which.RESTART -> host.showNotice(PLAYBACK_LATER)
            TitleButtons.Which.EPISODES -> focusEpisodes(toTarget = true)
            TitleButtons.Which.SOURCES -> host.showNotice(SOURCES_LATER)
            TitleButtons.Which.MY_LIST -> {
                val r = host.repo ?: return
                val inList = !r.inMyList(opened.id)
                host.hold.setInList(details.title, inList)
                header.buttons.setMyList(inList)
            }
            TitleButtons.Which.MORE -> openMoreWindow()
        }
    }

    override fun onButtonFocused(which: TitleButtons.Which) {
        part = Part.BUTTONS
        press.cancel()
        main.removeCallbacks(follow)
        header.unfollow()
        glide.glideTo(0f)
        updateImages()
    }

    override fun onButtonsDown(which: TitleButtons.Which) = moveDownFrom(Part.BUTTONS)

    override fun onLeftEdge() = host.focusRail()

    // ---- Rows -------------------------------------------------------------------------------------------------------

    override fun onItemFocused(row: TitleRow, index: Int) {
        press.cancel()
        part = partOf(row)
        if (row !== episodes) {
            main.removeCallbacks(follow)
            header.unfollow()
        }
        when (row) {
            pills -> {
                val n = seasons.getOrNull(index)?.number
                main.removeCallbacks(settleSeason)
                if (n != null && n != shownSeason) {
                    pendingSeason = n
                    main.postDelayed(settleSeason, VodSizes.SETTLE_MS)
                } else {
                    pendingSeason = null
                }
            }
            episodes -> {
                main.removeCallbacks(follow)
                main.postDelayed(follow, VodSizes.SETTLE_MS)
            }
        }
        if (isShow) glide.glideTo(if (TitleRules.glided(part, isShow)) glideTarget() else 0f)
        updateImages()
    }

    override fun onRowLeft(row: TitleRow): Boolean {
        // Covered by the page opened from it (or gone): the row stays as it was, its card grown, for coming back.
        if (paused || released) return false
        // Into its small window: the card stays grown (and the header on its episode) until the window closes.
        if (host.hold.isOpen && host.hold.heldRow === row) return false
        press.cancel()
        when (row) {
            episodes -> {
                main.removeCallbacks(follow)
                header.unfollow()
            }
            more -> pendingMore?.let { showMore(it) }
            pills -> pendingSeason?.let { applySeason(it) }
        }
        return true
    }

    override fun onItemKey(row: TitleRow, index: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) onHold(row, index)
                KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) onPick(row, index)
            }
            return true
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        press.cancel()
        val p = partOf(row)
        when (keyCode) {
            // One card (or pill) per press, also while held: the row glides behind; nothing wraps.
            KeyEvent.KEYCODE_DPAD_RIGHT -> row.items.getOrNull(index + 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) host.focusRail() else row.items.getOrNull(index - 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> moveDownFrom(p)
            KeyEvent.KEYCODE_DPAD_UP -> moveUpFrom(p)
            else -> return false
        }
        return true
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onPick(row: TitleRow, index: Int) {
        when (row) {
            pills -> moveDownFrom(Part.PILLS)
            episodes -> {
                val ep = shownEpisodes.getOrNull(index) ?: return
                host.showNotice(if (ep.aired(System.currentTimeMillis())) PLAYBACK_LATER else TitleRules.airs(ep, thisYear()))
            }
            more -> {
                val card = more.cards.getOrNull(index) ?: return
                resolve(card.title) { host.openNext(it) }
            }
        }
    }

    private fun onHold(row: TitleRow, index: Int) {
        val r = host.repo ?: return
        val hold = host.hold
        when (row) {
            episodes -> {
                val ep = shownEpisodes.getOrNull(index) ?: return
                // Nothing to mark on one that hasn't aired.
                if (!ep.aired(System.currentTimeMillis())) return
                val anchor = row.cardViewAt(index) ?: return
                val watched = r.isEpisodeWatched(ep)
                val buttons = listOf(
                    HoldPopup.Button(if (watched) "Mark unwatched" else "Mark watched") {
                        hold.close()
                        r.setEpisodesWatched(listOf(ep), !watched)
                        host.showNotice(if (watched) HoldWindow.UNMARKED else HoldWindow.MARKED)
                    },
                    HoldPopup.Button("Mark watched up to here") {
                        hold.close()
                        r.markWatchedUpTo(details, ep)
                        host.showNotice(HoldWindow.MARKED)
                    },
                )
                val name = listOfNotNull(TitleRules.episodeCode(ep.ref), ep.name.takeIf { it.isNotBlank() }).joinToString(" · ")
                hold.open(name, buttons, anchor, row.items[index], hold.area(VodSizes.HOLD_INSET), row, this) { takeFocusBack() }
            }
            more -> {
                val card = more.cards.getOrNull(index) ?: return
                resolve(card.title) { found ->
                    val anchor = row.cardViewAt(index) ?: return@resolve
                    if (!anchor.isShown) return@resolve
                    hold.openPoster(card.copy(title = found), false, anchor, row.items[index], hold.area(VodSizes.HOLD_INSET), row, this) { takeFocusBack() }
                }
            }
        }
    }

    /** [t] with its IMDb id: at once, or once the repository finds it ("Couldn't load this title" if it can't). */
    private fun resolve(title: Title, then: (Title) -> Unit) {
        if (title.hasImdbId) {
            then(title)
            return
        }
        val r = host.repo ?: return
        resolveCancel?.cancel()
        resolveCancel = r.resolve(title) { found ->
            resolveCancel = null
            if (released || paused) return@resolve
            if (found == null || !found.hasImdbId) host.showNotice(COULDNT_OPEN) else then(found)
        }
    }

    // ---- Season pills, the header following an episode -------------------------------------------------------------

    private val settleSeason = Runnable { pendingSeason?.let { applySeason(it) } }

    /** Shows season [n]'s cards (rebuilt in place) and marks its pill. */
    private fun applySeason(n: Int) {
        main.removeCallbacks(settleSeason)
        pendingSeason = null
        if (n == shownSeason && cardsSeason == n) return
        shownSeason = n
        pills.markSelected(seasonIndex(n))
        showSeasonCards()
    }

    /** Focus has rested on an episode card: the header says what it is. */
    private val follow = Runnable {
        val i = episodes.focusedIndex()
        val ep = shownEpisodes.getOrNull(i) ?: return@Runnable
        val now = System.currentTimeMillis()
        val facts = TitleRules.episodeFacts(ep, host.repo?.positionOf(ep), now, thisYear())
        val overview = ep.overview?.takeIf { it.isNotBlank() } ?: header.storyShown
        header.follow(facts, overview)
    }

    // ---- The (⋯) window and Details ---------------------------------------------------------------------------------

    private fun openMoreWindow() {
        val r = host.repo ?: return
        val hold = host.hold
        val title = details.title
        val now = System.currentTimeMillis()
        // A show reads as its episodes' ✓ marks do (the show's own mark covers only the episodes aired when it was
        // set): with episodes aired since, it offers Mark watched again. Before its episodes are known, its own mark.
        val watched = if (isShow && details.episodes.isNotEmpty()) {
            TitleRules.allWatched(details.episodes, now) { r.isEpisodeWatched(it) }
        } else {
            r.isWatched(opened.id)
        }
        val buttons = ArrayList<HoldPopup.Button>(3)
        buttons += HoldPopup.Button(if (watched) "Mark unwatched" else "Mark watched") { hold.markWatched(title, !watched) }
        if (r.inContinueWatching(opened.id)) buttons += HoldPopup.Button("Remove from Continue Watching") { hold.removeFromContinue(title) }
        buttons += HoldPopup.Button("Details") { openDetails() }
        val anchor = header.buttons.moreButton
        hold.open(title.name, buttons, anchor, anchor, hold.area(VodSizes.HOLD_INSET)) { takeFocusBack() }
    }

    /** Details: the whole story, the facts, all the cast and crew, and where each came from (a wide settings window). */
    private fun openDetails() {
        val back = header.buttons.moreButton
        host.hold.close(fade = false, focusBack = false)
        val d = details
        host.sidePanel.open(d.title.name, onClose = {
            if (!released && back.isShown) back.requestFocus() else if (!released) takeFocusBack()
        }, wide = true) { titleDetails(d) }
    }

    // ---- The glide and pictures -------------------------------------------------------------------------------------

    /** How far a show's page glides up for More Like This (its band ends on screen; the buttons stay just in view). */
    private fun glideTarget(): Float {
        val top = column.topOf(more)
        if (!isShow || top < 0 || root.height <= 0) return 0f
        val moreBottom = (top + more.bandHeight).toFloat()
        val buttonsTop = (header.top + header.buttonsTop()).toFloat()
        return VodSizes.glide(moreBottom, buttonsTop, root.height.toFloat(), t.dpf(VodSizes.GLIDE_KEEP_TOP))
    }

    /** Rows came or went (laid out again): a page glided for More Like This stays on it, wherever it now is. */
    private fun onRowsMoved() {
        if (isShow) {
            val want = if (TitleRules.glided(part, isShow) && more.focusedIndex() >= 0) glideTarget() else 0f
            if (want != glide.target) glide.snap(want)
        }
        updateImages()
    }

    /**
     * Pictures for the cards near each row's chosen one, for the rows that show where the page is heading (More Like
     * This on a show: once its posters are in view); the others let theirs go.
     */
    private fun updateImages() {
        if (paused || released) return
        if (episodes.visibility == View.VISIBLE) {
            val after = if (part == Part.MORE) VodSizes.EPISODE_BIND_AFTER - 1 else VodSizes.EPISODE_BIND_AFTER
            val range = RowMath.boundCards(episodes.chosen, episodes.items.size, 1, after)
            if (range != episodes.boundRange) episodes.bindImages(range)
        }
        if (more.visibility == View.VISIBLE) {
            val top = column.topOf(more)
            val postersTop = top + t.dp(VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER)) - glide.target
            val inView = !isShow || (top >= 0 && root.height > 0 && postersTop < root.height)
            if (inView) {
                val after = if (isShow) VodSizes.POSTER_BIND_AFTER - 1 else VodSizes.POSTER_BIND_AFTER
                val range = RowMath.boundCards(more.chosen, more.items.size, 1, after)
                if (range != more.boundRange) more.bindImages(range)
            } else if (more.boundRange != IntRange.EMPTY) {
                more.releaseImages()
            }
        }
    }

    private fun thisYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private companion object {
        const val PLAYBACK_LATER = "Playback comes next"
        const val SOURCES_LATER = "Sources come next"
        const val COULDNT_OPEN = "Couldn't load this title"
    }
}
