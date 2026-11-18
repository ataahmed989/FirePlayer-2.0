package app.fireplayer.vod.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.live.ui.HoldPopup
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.Spring
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.VodRepository
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * One of Films & Shows' pages: Home, Movies or TV Shows (design-brief.md §6.3). Under a name-and-clock line, a header
 * (195 dp; on Home with the reserved top-right window) and the page's rows, from [VodRepository.rows]. Rows that turn
 * out empty are hidden.
 *
 * Moving: Left/Right go card by card along a row, which glides like the Episodes sheet ([TitleRow]); Up/Down go row by
 * row, the page gliding with a Spring: below the first row the header slides away and the chosen row sits just under
 * the name line; back on the first row the header returns. Left from a row's first card goes to the rail. OK on a
 * poster says the title page comes next, on a Continue card that playback comes next (later steps), on See all or a
 * genre opens [SeeAllPage]; OK held on a title opens a small window ([HoldPopup]): + Add to list (or − Remove from
 * list), Mark watched, and on Continue cards Remove.
 *
 * Lean: pictures only for the rows on screen and near the chosen card, let go when the page is hidden or stopped;
 * rows laid out once, then moved by translation and scale alone; cached rows shown at once and refreshed behind.
 */
class VodPage(
    private val ctx: Context,
    private val host: Host,
    private val repoOf: () -> VodRepository?,
    val page: Page,
) : TitleRow.Listener, SeeAllPage.Host {

    /** What the shell gives a page: the same as Live TV's (HomeActivity implements both). */
    interface Host {
        val tokens: Tokens
        fun focusRail()
        fun railHasFocus(): Boolean
        /** The settings window or a dialog is open over the page: its header holds still. */
        fun overlayOpen(): Boolean
    }

    private val t = host.tokens
    private val main = Handler(Looper.getMainLooper())
    private var repo: VodRepository? = repoOf()

    val root = FrameLayout(ctx)
    private val nameText = t.label(ctx, 20f, t.bold)
    private val clockText = t.label(ctx, 13f, t.regular, t.textMuted)
    /** Under the name line; clips, so nothing shows above it. */
    private val viewport = FrameLayout(ctx)
    private val column = PageColumn(ctx, t)
    private val headerBand = FrameLayout(ctx)
    private val headerW = if (page == Page.HOME) VodSizes.homeHeaderW() else VodSizes.tabHeaderW()
    private val header = VodHeader(ctx, t, headerW)
    private val window: RecentWindow? = if (page == Page.HOME) RecentWindow(ctx, t) else null
    private val rows = ArrayList<TitleRow>()
    private var rowsBuilt = false
    private var seeAll: SeeAllPage? = null
    /** The row and item See all was opened from. */
    private var seeAllFrom: Pair<TitleRow, Int>? = null

    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val emptyText = t.label(ctx, 18f, t.semibold).apply { maxLines = 3; gravity = Gravity.CENTER }
    private val emptyButton = t.label(ctx, 15f, t.semibold)
    private val notice = t.label(ctx, 15f).apply { maxLines = 2 }
    private val popup = HoldPopup(ctx, t)
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())
    /** The column's upward offset, px: 0 shows the header. */
    private val pageSpring = Spring(omega = 14f, settle = 0.5f) { column.translationY = -it }

    private var shown = false
    private var started = false
    private var resumed = false
    private var released = false
    private var lastLoad = 0L
    /** Focus was asked for before any row showed: it comes in with the first row. */
    private var focusPending = false
    /** What held focus when it was asked for (the rail's tab): focus moved on from it, the wait ends. */
    private var pendingFrom: View? = null
    /** The row focus was last in (focus memory for the rail, windows and tab switches). */
    private var lastRow: TitleRow? = null
    /** The row holding focus now, or null. */
    private var focusedRow: TitleRow? = null
    /** The row the page's offset is anchored to, and its top then: rows appearing above it don't move it on screen. */
    private var anchorRow: TitleRow? = null
    private var anchorTop = -1
    private var headerCancel: Cancel? = null
    private var heldView: View? = null
    private var heldRow: TitleRow? = null
    private var backClosedPopup = false
    private var seeAllBackDown = false
    private val libraryListener: () -> Unit = { onLibraryChanged() }

    private val pageName = when (page) {
        Page.HOME -> "Home"
        Page.MOVIES -> "Movies"
        Page.SHOWS -> "TV Shows"
    }

    init {
        root.visibility = View.GONE
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val pageX = t.dp(VodSizes.PAGE_X)

        // The name line: the page's name, then the date and time.
        val nameLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        nameText.text = pageName
        nameLine.addView(nameText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Fixed width: the clock's new minute only redraws it.
        nameLine.addView(clockText, LinearLayout.LayoutParams(t.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(14) })
        root.addView(nameLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = pageX
            topMargin = t.dp(VodSizes.NAME_TOP)
        })

        // The header band: the header card, and on Home the window beside it.
        headerBand.clipChildren = false
        headerBand.addView(header, FrameLayout.LayoutParams(t.dp(headerW), t.dp(VodSizes.HEADER_H)).apply { leftMargin = pageX })
        window?.let {
            headerBand.addView(it, FrameLayout.LayoutParams(t.dp(VodSizes.WINDOW_W), t.dp(VodSizes.HEADER_H)).apply {
                leftMargin = t.dp(VodSizes.windowX())
            })
        }
        header.blocked = { host.overlayOpen() || popup.isOpen }
        column.header = headerBand
        column.onRowsMoved = { onRowsMoved() }
        viewport.clipChildren = true
        viewport.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.VIEWPORT_TOP)
        })

        // Nothing at all to show: a way to try again.
        emptyText.text = "Couldn't load titles. Check the connection."
        emptyButton.apply {
            text = "Try again"
            isFocusable = true
            gravity = Gravity.CENTER
            setPadding(t.dp(22), t.dp(10), t.dp(22), t.dp(10))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(t.accent, t.text)))
            setOnClickListener { retry() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { host.focusRail(); true }
                else keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }
        empty.addView(emptyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        empty.addView(emptyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(18)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        empty.visibility = View.GONE
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.VIEWPORT_TOP)
        })

        notice.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10))
        notice.visibility = View.GONE
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = t.dp(30)
        })
        root.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        popup.onClose = { focusBack ->
            val back = heldView
            val row = heldRow
            heldView = null
            heldRow = null
            when {
                // Back on the card held (or, if a change took it away, the row's chosen card, else the page's).
                focusBack && back != null && back.isShown -> back.requestFocus()
                focusBack && row != null && row.visibility == View.VISIBLE && row.items.isNotEmpty() -> row.focusChosen()
                focusBack -> takeFocus()
                // Something else took focus (the settings window): the held card shrinks back.
                row != null && row.focusedIndex() < 0 -> if (onRowLeft(row)) row.motion.shrink()
            }
            updateHeaderPause()
        }
        buildRows()
        applyLook()
    }

    // ---- Lifecycle --------------------------------------------------------------------------------------------------

    /** The page's tab is selected: shown, and running while the screen is started. */
    fun show() {
        if (released) return
        shown = true
        root.visibility = View.VISIBLE
        resume()
    }

    /** Another tab: hidden; See all and the small window close, its pictures go. */
    fun hide() {
        shown = false
        focusPending = false
        pendingFrom = null
        if (popup.isOpen) popup.close(fade = false, focusBack = false)
        closeSeeAll(focusBack = false)
        pause()
        root.visibility = View.GONE
    }

    /** The screen started (onStart). */
    fun start() {
        started = true
        resume()
    }

    /** The screen stopped (onStop): timers stop, pictures go. */
    fun stop() {
        started = false
        pause()
    }

    fun release() {
        pause()
        released = true
        for (row in rows) row.cancel?.cancel()
        headerCancel?.cancel()
        window?.release()
        seeAll?.release()
        if (popup.isOpen) popup.close(fade = false, focusBack = false)
        main.removeCallbacksAndMessages(null)
    }

    private fun resume() {
        if (!shown || !started || resumed || released) return
        resumed = true
        repo?.addLibraryListener(libraryListener)
        tickClock()
        window?.refresh()
        val now = SystemClock.elapsedRealtime()
        if (lastLoad == 0L || now - lastLoad > RELOAD_MS) loadAll() else reloadLibrary()
        updateHeaderPause()
        updateImages()
        if (seeAll?.isOpen == true) seeAll?.bindImages()
    }

    private fun pause() {
        if (!resumed) return
        resumed = false
        repo?.removeLibraryListener(libraryListener)
        main.removeCallbacks(clockTick)
        header.release()
        if (pageSpring.moving) pageSpring.snap(pageSpring.target)
        for (row in rows) {
            row.motion.settle()
            row.releaseImages()
        }
        seeAll?.settle()
        seeAll?.releaseImages()
    }

    /** Memory is short: pictures of rows other than the chosen one go. */
    fun trimImages() {
        val keep = focusedRow ?: lastRow
        for (row in rows) if (row !== keep) row.releaseImages()
    }

    /** The look changed: surfaces made again, cards redrawn. */
    fun applyLook() {
        header.applyLook()
        window?.applyLook()
        Glass.background(notice, Glass.Kind.POPUP)
        Glass.background(emptyButton, Glass.Kind.PILL)
        popup.applyLook()
        for (row in rows) row.applyLook()
    }

    // ---- Focus ------------------------------------------------------------------------------------------------------

    /** Into the page (from the rail): the card chosen last, in See all when it's open; else the first row's first. */
    fun takeFocus() {
        if (!shown) return
        val sa = seeAll
        if (sa != null && sa.isOpen) {
            sa.takeFocus()
            return
        }
        if (empty.visibility == View.VISIBLE) {
            emptyButton.requestFocus()
            return
        }
        val row = lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() } ?: firstReadyRow()
        if (row == null || !row.focusChosen()) waitForRows()
    }

    /** Focus asked for before the page's first row is in: it goes there when it comes, unless the viewer moves on. */
    private fun waitForRows() {
        focusPending = true
        pendingFrom = root.rootView.findFocus()
    }

    /**
     * Whether focus asked for while loading is still wanted: not once the viewer has moved on from where it was (to
     * another tab in the rail). Where it was may have gone meanwhile (a window that closed): then it still is.
     */
    private fun stillPending(): Boolean {
        if (!focusPending) return false
        val from = pendingFrom
        val now = root.rootView.findFocus()
        if (from != null && now != null && now !== from && from.isShown) {
            focusPending = false
            pendingFrom = null
        }
        return focusPending
    }

    /**
     * The page's first row with titles, once every row above it has answered (a row answering later would come in
     * above it, and the page would slide its header away); null while it waits.
     */
    private fun firstReadyRow(): TitleRow? =
        rows.firstOrNull { !it.answered || it.visibility == View.VISIBLE }
            ?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }

    /** The page's Back: closes the small window, then See all. False when it's the shell's (to the rail). */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (popup.isOpen || backClosedPopup) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                popup.close()
                backClosedPopup = true
            } else if (event.action == KeyEvent.ACTION_UP) {
                backClosedPopup = false
            }
            return true
        }
        if (seeAll?.isOpen == true) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                seeAllBackDown = true
            } else if (event.action == KeyEvent.ACTION_UP && seeAllBackDown) {
                seeAllBackDown = false
                closeSeeAll(focusBack = true)
            }
            return true
        }
        seeAllBackDown = false
        return false
    }

    private fun visibleRows(): List<TitleRow> = rows.filter { it.visibility == View.VISIBLE }

    /** Where the column goes with [row] chosen: 0 on the first row (the header shows), else that row's top. */
    private fun targetFor(row: TitleRow?): Float {
        if (row == null) return 0f
        val vis = visibleRows()
        val k = vis.indexOf(row)
        if (k < 0) return pageSpring.target
        return RowMath.pageTarget(k, 0) { column.topOf(vis[it]).coerceAtLeast(0).toFloat() }
    }

    override fun onItemFocused(row: TitleRow, index: Int) {
        focusPending = false
        pendingFrom = null
        press.cancel()
        if (focusedRow !== row || anchorRow !== row) {
            anchorRow = row
            anchorTop = column.topOf(row)
        }
        focusedRow = row
        lastRow = row
        pageSpring.glideTo(targetFor(row))
        if (row.spec.continueRow && !row.isSeeAll(index)) {
            row.cards.getOrNull(index)?.let { header.follow(it) }
        } else {
            header.unfollow()
        }
        updateHeaderPause()
        updateImages()
    }

    override fun onRowLeft(row: TitleRow): Boolean {
        // Into its small window: the card stays grown and the header on its title until the window closes.
        if (popup.isOpen && heldRow === row) return false
        if (focusedRow === row) focusedRow = null
        press.cancel()
        // New cards held back while the viewer was in the row.
        row.pending?.let { (cards, seeAll) -> showCards(row, cards, seeAll) }
        if (row.spec.continueRow && focusedRow?.spec?.continueRow != true) header.unfollow()
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
        // Any other key between a press and its release: that release does nothing.
        press.cancel()
        when (keyCode) {
            // One card per press, also while held: the row glides behind.
            KeyEvent.KEYCODE_DPAD_RIGHT -> row.items.getOrNull(index + 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) host.focusRail() else row.items.getOrNull(index - 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(row, 1)
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(row, -1)
            else -> return false
        }
        return true
    }

    /** Up or Down: the row above or below (shown rows only; no wrapping, a held key stops at the end). */
    private fun moveRow(row: TitleRow, dir: Int) {
        val vis = visibleRows()
        val k = vis.indexOf(row)
        vis.getOrNull(k + dir)?.focusChosen()
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onPick(row: TitleRow, index: Int) {
        when {
            row.style == RowStyle.GENRES -> row.genres.getOrNull(index)?.let { openSeeAll(row, index, genreSpec(row.spec, it)) }
            row.isSeeAll(index) -> openSeeAll(row, index, row.spec)
            row.spec.continueRow -> showNotice(PLAYBACK_LATER)
            else -> showNotice(TITLE_LATER)
        }
    }

    private fun onHold(row: TitleRow, index: Int) {
        if (row.style == RowStyle.GENRES || row.isSeeAll(index)) return
        val card = row.cards.getOrNull(index) ?: return
        val anchor = row.cardViewAt(index) ?: return
        openHold(card, row.spec.continueRow, anchor, row.items[index], row)
    }

    private fun genreSpec(of: RowSpec, genre: String): RowSpec {
        val kind = (of.source as? Source.Genres)?.kind ?: if (page == Page.SHOWS) Kind.SERIES else Kind.MOVIE
        return RowSpec(key = "${of.key}:$genre", label = genre, style = RowStyle.POSTER, source = Source.Catalog(kind, "top", genre = genre))
    }

    // ---- The small window (hold OK) --------------------------------------------------------------------------------

    /**
     * The small window for [card], beside [anchor] (the grown card); [focusBack] gets focus back when it closes. [row]:
     * the row it was opened in (null in See all), whose card stays grown meanwhile.
     */
    private fun openHold(card: Card, continueRow: Boolean, anchor: View, focusBack: View, row: TitleRow? = null) {
        val r = repo ?: return
        val title = card.title
        val buttons = ArrayList<HoldPopup.Button>(3)
        buttons += listButton(title, r.inMyList(title.id))
        buttons += HoldPopup.Button(if (r.isWatched(title.id)) "Mark unwatched" else "Mark watched") { toggleWatched(title) }
        if (continueRow) buttons += HoldPopup.Button("Remove") { removeFromContinue(title) }
        heldView = focusBack
        heldRow = row
        val area = RectF(t.dpf(12f), t.dpf(VodSizes.VIEWPORT_TOP), root.width - t.dpf(12f), root.height - t.dpf(12f))
        popup.open(title.name, buttons, inRoot(anchor), area)
        updateHeaderPause()
    }

    private fun listButton(title: Title, inList: Boolean) =
        HoldPopup.Button(if (inList) "− Remove from list" else "+ Add to list") { toggleList(title) }

    /** + Add to list / − Remove from list: changed at once (the rows too), and the window closes a moment later. */
    private fun toggleList(title: Title) {
        val r = repo ?: return
        val inList = !r.inMyList(title.id)
        r.setInMyList(title, inList)
        popup.update(0, listButton(title, inList))
        popup.closeAfter(LIST_CLOSE_MS)
        for (row in rows) {
            if (row.spec.source !is Source.MyList) continue
            val cards = RowRules.myListAfter(row.cards, title, inList)
            showCards(row, cards.take(ROW_CARDS), row.hasSeeAll || cards.size > ROW_CARDS, mine = true)
        }
        val sa = seeAll
        if (!inList && sa != null && sa.isOpen && sa.spec?.source is Source.MyList) sa.remove(title.id)
    }

    /** Mark watched also takes the title out of Continue Watching (until it's played again): at once, like Remove. */
    private fun toggleWatched(title: Title) {
        val r = repo ?: return
        popup.close()
        val watched = !r.isWatched(title.id)
        r.setWatched(title, watched)
        if (watched) dropFromContinue(title.id)
        showNotice(if (watched) "Marked as watched" else "Marked as not watched")
    }

    /** Remove: out of Continue Watching (its resume point stays), at once. */
    private fun removeFromContinue(title: Title) {
        val r = repo ?: return
        popup.close()
        r.removeFromContinueWatching(title.id)
        dropFromContinue(title.id)
    }

    /** [id] has left Continue Watching: its cards go from the continue rows (and their See all) now. */
    private fun dropFromContinue(id: String) {
        for (row in rows) {
            if (!row.spec.continueRow && row.spec.source !is Source.ContinueWatching) continue
            if (row.cards.none { it.title.id == id }) continue
            showCards(row, row.cards.filter { it.title.id != id }, row.hasSeeAll, mine = true)
        }
        val sa = seeAll
        if (sa != null && sa.isOpen && sa.spec?.continueRow == true) sa.remove(id)
    }

    /** [v]'s rectangle as drawn (grown, moved), in the page's coordinates. */
    private fun inRoot(v: View): RectF {
        val a = IntArray(2)
        v.getLocationInWindow(a)
        val b = IntArray(2)
        root.getLocationInWindow(b)
        val left = (a[0] - b[0]).toFloat()
        val top = (a[1] - b[1]).toFloat()
        return RectF(left, top, left + v.width * v.scaleX, top + v.height * v.scaleY)
    }

    // ---- See all ----------------------------------------------------------------------------------------------------

    private fun openSeeAll(row: TitleRow, index: Int, spec: RowSpec) {
        val r = repo ?: return
        val sa = seeAll ?: SeeAllPage(ctx, t, r, this).also {
            seeAll = it
            root.addView(it.root, root.indexOfChild(viewport) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = t.dp(VodSizes.VIEWPORT_TOP)
            })
        }
        seeAllFrom = row to index
        sa.open(spec, if (spec === row.spec) row.cards else emptyList())
        // Focus into the grid before the rows hide (a hidden row holding focus would hand it anywhere).
        sa.takeFocus()
        if (pageSpring.moving) pageSpring.snap(pageSpring.target)
        viewport.visibility = View.INVISIBLE
        nameText.text = "${spec.label} · $pageName"
        updateHeaderPause()
        // Only the grid's pictures (and the row it came from, for coming back).
        for (other in rows) if (other !== row) other.releaseImages()
    }

    private fun closeSeeAll(focusBack: Boolean) {
        val sa = seeAll ?: return
        if (!sa.isOpen) return
        val from = seeAllFrom
        seeAllFrom = null
        viewport.visibility = View.VISIBLE
        if (focusBack) {
            // Back on the card it was opened from, else the row chosen last, else the first: while the grid still
            // holds focus (closed first, it would hand focus to the rail's first tab).
            val origin = from?.first?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }
            if (origin != null && from != null) origin.chosen = from.second.coerceIn(0, origin.items.size - 1)
            val target = origin
                ?: lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }
                ?: visibleRows().firstOrNull()
            if (target == null || !target.focusChosen()) {
                if (empty.visibility == View.VISIBLE) emptyButton.requestFocus() else host.focusRail()
            }
        }
        sa.close()
        nameText.text = pageName
        updateHeaderPause()
        updateImages()
    }

    override fun focusRail() = host.focusRail()

    override fun onGridPick(card: Card, spec: RowSpec) = showNotice(if (spec.continueRow) PLAYBACK_LATER else TITLE_LATER)

    override fun onGridHold(card: Card, spec: RowSpec, view: CardView) = openHold(card, spec.continueRow, view, view)

    // ---- Loading ----------------------------------------------------------------------------------------------------

    private fun buildRows() {
        if (rowsBuilt) return
        val r = repo ?: repoOf().also { repo = it } ?: return
        rowsBuilt = true
        for (spec in r.rows(page)) {
            val row = TitleRow(ctx, t, spec, this)
            rows += row
            column.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.bandHeight))
        }
        if (resumed) r.addLibraryListener(libraryListener)
    }

    private fun retry() {
        buildRows()
        loadAll()
        checkEmpty()
    }

    private fun loadAll() {
        val r = repo
        if (r == null) {
            checkEmpty()
            return
        }
        lastLoad = SystemClock.elapsedRealtime()
        headerCancel?.cancel()
        headerCancel = r.header(page) { feed -> if (!released) header.setFeed(feed.titles) }
        for (row in rows) {
            row.answered = false
            load(row)
        }
        checkEmpty()
    }

    /** My List, Continue Watching and Next Episodes again (the library changed, or the page came back). */
    private fun reloadLibrary() {
        for (row in rows) if (RowRules.isLibrary(row.spec.source)) load(row)
    }

    private fun onLibraryChanged() {
        if (!released && resumed) reloadLibrary()
    }


    private fun load(row: TitleRow) {
        val r = repo ?: return
        row.cancel?.cancel()
        val gen = ++row.generation
        row.cancel = if (row.style == RowStyle.GENRES) {
            val kind = (row.spec.source as? Source.Genres)?.kind ?: if (page == Page.SHOWS) Kind.SERIES else Kind.MOVIE
            r.genres(kind) { names ->
                if (released || gen != row.generation) return@genres
                row.answered = true
                showGenres(row, names)
            }
        } else {
            // One more than shown: whether there are more than 15 (a library row's See all).
            r.load(row.spec, 0, ROW_CARDS + 1) { cards, fresh ->
                if (released || gen != row.generation) return@load
                onCards(row, cards, fresh)
            }
        }
    }

    private fun onCards(row: TitleRow, cards: List<Card>, fresh: Boolean) {
        // An empty cached answer: the fresh one follows (a library row's answer is always final).
        if (!RowRules.isFinal(row.spec.source, cards.isEmpty(), fresh)) return
        row.answered = true
        val shownCards = cards.take(ROW_CARDS)
        val seeAll = RowRules.seeAll(row.spec.source, cards.size)
        // The viewer is in this row and the new cards would move what they're on: held until they leave it.
        if (!RowRules.keepsFocus(row.focusedIndex(), row.cards, row.hasSeeAll, shownCards, seeAll)) {
            row.pending = shownCards to seeAll
            return
        }
        showCards(row, shownCards, seeAll)
    }

    /**
     * Puts [cards] in [row] (in place), shows or hides the row, and keeps focus on something that stays. [mine]: the
     * viewer's own change (applied at once even in the row they're in).
     */
    private fun showCards(row: TitleRow, cards: List<Card>, seeAll: Boolean, mine: Boolean = false) {
        row.pending = null
        val same = !mine && row.visibility == View.VISIBLE && row.hasSeeAll == seeAll &&
            row.cards.size == cards.size && row.cards.indices.all { row.cards[it] == cards[it] }
        if (same) return
        if (cards.isEmpty()) {
            hideRow(row)
            checkEmpty()
            // A row above answering empty: focus asked for may go to one below it now.
            takePendingFocus()
            return
        }
        // Focus off items that go, before they go.
        val target = RowRules.focusAfter(row.focusedIndex(), row.cards.size, row.hasSeeAll, cards.size, seeAll)
        if (target >= 0) row.items.getOrNull(target)?.requestFocus()
        row.bind(cards, seeAll)
        if (row.visibility != View.VISIBLE) {
            row.chosen = 0
            row.motion.snap(0, isGrown = false)
            row.visibility = View.VISIBLE
        }
        // In the Continue row, a card taken away: the header follows the card now chosen.
        val at = row.focusedIndex()
        if (row.spec.continueRow && at >= 0) row.cards.getOrNull(at)?.let { header.follow(it) } ?: header.unfollow()
        checkEmpty()
        updateImages()
        takePendingFocus()
    }

    private fun showGenres(row: TitleRow, names: List<String>) {
        if (names.isEmpty()) {
            hideRow(row)
            checkEmpty()
            takePendingFocus()
            return
        }
        if (row.genres == names && row.visibility == View.VISIBLE) return
        row.bindGenres(names)
        if (row.visibility != View.VISIBLE) {
            row.chosen = 0
            row.motion.snap(0, isGrown = false)
            row.visibility = View.VISIBLE
        }
        checkEmpty()
        takePendingFocus()
    }

    /** A row with nothing in it goes; focus in it moves to the next row shown, else the one before, else the rail. */
    private fun hideRow(row: TitleRow) {
        if (row.visibility != View.VISIBLE) return
        if (row.focusedIndex() >= 0) {
            val vis = visibleRows()
            val k = vis.indexOf(row)
            val to = vis.getOrNull(k + 1) ?: vis.getOrNull(k - 1)
            if (to == null || !to.focusChosen()) host.focusRail()
        }
        if (lastRow === row) lastRow = null
        if (anchorRow === row) anchorRow = null
        row.releaseImages()
        row.visibility = View.GONE
        row.bind(emptyList(), seeAll = false)
    }

    /**
     * A row came or went: focus asked for meanwhile goes into the page's first row, once every row above it has
     * answered (not into whichever row answered first).
     */
    private fun takePendingFocus() {
        if (!shown || !resumed) return
        // Asked for while loading (and the viewer stayed on the rail's tab), or nothing has focus at all: never taken
        // from the rail otherwise.
        if (!stillPending() && root.rootView.findFocus() != null) return
        val row = firstReadyRow() ?: return
        if (row.focusChosen()) {
            focusPending = false
            pendingFrom = null
        }
    }

    private fun checkEmpty() {
        val inPage = root.findFocus() != null
        // Genre chips alone are no page (offline, each would open an empty grid): they don't count, and hide with the
        // rest until the next load brings them back with the titles.
        val nothing = repo == null ||
            (rows.all { it.answered } && rows.none { it.visibility == View.VISIBLE && it.style != RowStyle.GENRES })
        if (nothing) for (r in rows) if (r.style == RowStyle.GENRES) hideRow(r)
        val was = empty.visibility == View.VISIBLE
        if (!nothing && was && empty.hasFocus()) {
            // Try again (or a reload): focus moves before the message hides (a hidden view holding focus would hand it
            // to the rail's first tab). With no row in yet, it waits on this page's tab: the first row takes it, or Try
            // again if nothing comes.
            val r = firstReadyRow()
            if (r == null || !r.focusChosen()) {
                host.focusRail()
                waitForRows()
            }
        }
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        if (nothing && !was && (stillPending() || inPage)) {
            focusPending = false
            pendingFrom = null
            emptyButton.requestFocus()
        }
    }

    // ---- Layout changes, header and pictures ---------------------------------------------------------------------

    /** Rows appeared, went or changed height: the chosen row stays where it is on screen, then glides to its place. */
    private fun onRowsMoved() {
        val row = anchorRow?.takeIf { it.visibility == View.VISIBLE }
        if (row == null) {
            if (visibleRows().isNotEmpty() && focusedRow == null) pageSpring.snap(0f)
            updateHeaderPause()
            updateImages()
            return
        }
        val top = column.topOf(row)
        if (top < 0) return
        val shift = if (anchorTop >= 0) top - anchorTop else 0
        anchorTop = top
        if (shift != 0) pageSpring.snap(pageSpring.value + shift)
        pageSpring.glideTo(targetFor(row))
        updateHeaderPause()
        updateImages()
    }

    /** The header rotates only while it shows and nothing is open over the page. */
    private fun updateHeaderPause() {
        val active = resumed && !released && pageSpring.target == 0f && !popup.isOpen && seeAll?.isOpen != true
        if (active) header.resume() else header.pause()
    }

    /**
     * Pictures for the rows that show (where the page is heading) and the one above the chosen row, for the cards
     * near each row's chosen one; rows further away let theirs go. The next few posters are fetched ahead to disk.
     */
    private fun updateImages() {
        if (!resumed) return
        val vis = visibleRows()
        if (vis.isEmpty()) return
        val sa = seeAll
        if (sa != null && sa.isOpen) return
        val focus = focusedRow ?: lastRow
        val k = vis.indexOf(focus).coerceAtLeast(0)
        val offset = pageSpring.target
        val tops = FloatArray(vis.size) { column.topOf(vis[it]).toFloat() }
        val heights = FloatArray(vis.size) { vis[it].bandHeight.toFloat() }
        val bound = RowMath.boundRows(k, offset, t.dpf(VodSizes.VIEWPORT_H), tops, heights)
        vis.forEachIndexed { j, row ->
            when {
                // Not laid out yet: bound once the column's layout has placed it.
                tops[j] < 0f -> Unit
                j in bound -> {
                    val range = RowMath.boundCards(row.chosen, row.items.size)
                    if (range != row.boundRange) row.bindImages(range)
                }
                abs(j - k) > 2 -> if (row.boundRange != IntRange.EMPTY) row.releaseImages()
            }
        }
        // Ahead, to disk only: the two cards after the chosen row's window and the first of the row after next.
        vis.getOrNull(k)?.let { row ->
            val end = RowMath.boundCards(row.chosen, row.items.size).last
            for (i in end + 1..end + 2) row.cards.getOrNull(i)?.let { ImageLoader.prefetch(pictureOf(row, it)) }
        }
        vis.getOrNull(k + 2)?.let { row -> row.cards.take(8).forEach { ImageLoader.prefetch(pictureOf(row, it)) } }
    }

    private fun pictureOf(row: TitleRow, card: Card): String? =
        if (row.style == RowStyle.WIDE) wideArt(card) else card.title.poster

    // ---- Clock and notices -------------------------------------------------------------------------------------------

    private val clockTick = Runnable { tickClock() }

    private fun tickClock() {
        val now = System.currentTimeMillis()
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now)
        clockText.text = "$date · ${DateFormat.getTimeFormat(ctx).format(Date(now))}"
        main.removeCallbacks(clockTick)
        // On the next minute.
        main.postDelayed(clockTick, 60_000L - now % 60_000L + 50L)
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    companion object {
        /** Cards in a row (then See all). */
        const val ROW_CARDS = RowRules.ROW_CARDS
        /** Rows are asked again when the page comes back after this long (the repository answers from its cache). */
        private const val RELOAD_MS = 10L * 60 * 1000
        private const val LIST_CLOSE_MS = 450L
        private const val NOTICE_MS = 3_500L
        private const val TITLE_LATER = "Title page comes next"
        private const val PLAYBACK_LATER = "Playback comes next"
    }
}
