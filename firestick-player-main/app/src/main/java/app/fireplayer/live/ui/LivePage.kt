package app.fireplayer.live.ui

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.Programme
import app.fireplayer.playback.LiveSession
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SidePanel
import java.util.Date
import java.util.concurrent.Executors

/**
 * The Live TV tab: one experience of guide and player (design-layout, "Live TV guide and playback").
 *
 * Guide: playlist/group column, the miniature top left with the selected programme's details beside it, and the
 * compact grid below. The column collapses while the grid has focus. First Center on another channel tunes it in the
 * miniature; Center on the playing channel opens full screen; holding Back opens full screen; so does a period
 * without key presses ([LivePrefs.fullScreenAfterSec]). The miniature and full screen are the same player and
 * surface, so going between them never retunes. Center held on a channel or on a group opens a small window
 * ([HoldPopup]): a channel's star and Assign favorites, a group's Move to top and Hide category.
 *
 * Full screen: with the controls hidden, Up/Down and Channel +/- change channel (or only Channel +/- when
 * "Channel buttons only" is set, and then Up/Down show the controls): Up and Channel + to the next channel down the
 * guide's list, Down and Channel - to the one before. Center shows the controls; Back returns to the guide with its
 * group, channel and time as they were; TV Guide (the button or the remote's Guide key) returns to it on the channel
 * playing, on what's on now ([focusPlaying]). With the controls shown, the D-pad moves round them and
 * Back hides them first; Down swaps the buttons for the extra row ([LiveMore]: audio, display size, dimmer, sleep
 * timer, settings) and Up brings them back, as in the VOD player.
 */
class LivePage(private val activity: Activity, private val host: Host) {

    interface Host {
        val tokens: Tokens
        /** Live video at [rect] (root coordinates) with rounded corners, or the whole screen, or nowhere (null). */
        /**
         * Live video at [rect] (root coordinates) with rounded corners, or the whole screen ([full]), or nowhere (null).
         * With [animateMs] > 0 the picture moves and grows (or shrinks) there smoothly over that time (miniature ↔
         * full screen); otherwise it's placed at once.
         */
        fun placeVideo(rect: RectF?, radius: Float, full: Boolean, animateMs: Long = 0L)
        fun setChromeVisible(visible: Boolean)
        /** The tab rail beside the guide: shown (the groups column is open) or slid away (in the grid). */
        fun setRailShown(shown: Boolean, animateMs: Long = 0L)
        fun focusRail()
        fun railHasFocus(): Boolean
        /** The settings window or a dialog is open over the page: its timers wait. */
        fun overlayOpen(): Boolean
        /** What owns the live picture on this screen (its surface): a stale screen's stop is ignored. */
        val videoOwner: Any
        /**
         * Glass shows the blurred live picture (true: full-screen controls are up) or the wallpaper (false). The film
         * is only copied while glass is on screen, so with the controls hidden nothing is captured.
         */
        fun setFilmGlass(on: Boolean)
        fun openPlaylists()
        /**
         * The Live FirePlayer Settings window over this page (Menu, or the Settings button in full screen); [onClosed]
         * runs after it closes (the Settings button: focus back to it, the controls' timer again).
         */
        fun openPlayerSettings(onClosed: (() -> Unit)? = null)
        /**
         * Assign favorites over this page (from the guide): [groupId]'s channels, with [channel] selected, and Back goes
         * to [playlistId]'s groups; with no [groupId], the groups. Focus comes back where it was when it closes.
         */
        fun openAssignFavorites(playlistId: String, groupId: String?, channel: ChannelKey?)
        /** Manage groups over this page (a group's held window): [playlistId]'s groups, [groupId]'s row selected. */
        fun openManageGroups(playlistId: String, groupId: String?)
        /** The settings window on the right, where the full-screen extra row's windows open. */
        val sidePanel: SidePanel
        /** Display size or the screen dimmer changed: the full-screen picture is laid out and dimmed again. */
        fun pictureChanged()
    }

    private val t = host.tokens
    private val ctx: Context = activity
    private val db = LiveDb.get(ctx)
    private val sync = LiveSync.get(ctx)
    private val player = PlayerHost.get(ctx)
    private val prefs = LivePrefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    val root = FrameLayout(ctx)
    /** Everything but the full-screen controls: the groups column, and the content (miniature, details, guide). */
    private val guideLayer = FrameLayout(ctx)
    private val sidebarPanel = FrameLayout(ctx)
    /** A hairline at the column's right edge (glass), so the column reads as its own area without a panel. */
    private val columnRule = View(ctx)
    val sidebar = GuideSidebar(ctx, t)
    /** Miniature, details and guide: its left edge slides as the column opens and closes. */
    private val content = FrameLayout(ctx)
    /** Where the miniature goes. With nothing playing it's a panel with a hint; while playing it's see-through. */
    private val videoSlot = t.label(ctx, 14f, color = t.textMuted).apply {
        text = "Press OK on a channel to watch it here"
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(t.dp(24), 0, t.dp(24), 0)
    }
    private var placedAt: RectF? = null
    /** Back from full screen while the content moves: the shrink waits for the layout that puts the slot in place. */
    private var shrinkPending = false
    private val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** The channel's logo on its plate, before its name on the details' top line (as in the guide's rows). */
    private val detailLogo = LogoView(ctx)
    private val detailChannel = t.label(ctx, 16f, t.regular, t.text)
    private val detailStar = android.widget.ImageView(ctx)
    private val detailTitle = t.label(ctx, 25f, t.bold)
    private val detailMeta = t.label(ctx, 14.5f, t.regular, t.text)
    private val detailTime = t.label(ctx, 15f)
    private val detailBar = ProgressLine(ctx, t)
    private val detailLeft = t.label(ctx, 15f)
    private val detailDesc = t.label(ctx, 14.5f, color = t.textMuted).apply { maxLines = 3 }
    private val detailNext = t.label(ctx, 14f, color = t.textMuted)
    val guide = GuideView(ctx, t)
    private val controls = LiveControls(ctx, t)
    /** The extra row's windows and the sleep timer. */
    private val more = LiveMore(activity, host.sidePanel, player, controls, object : LiveMore.Callbacks {
        override fun windowClosed(from: View) { backFromWindow(from) }
        override fun pictureChanged() { host.pictureChanged() }
        override fun programmeEnd(): Long? = programmeNow()?.stop
        override fun sleepNow() { stopForSleep() }
    })
    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val emptyText = t.label(ctx, 18f, t.semibold).apply { maxLines = 3; gravity = Gravity.CENTER }
    private val emptyButton = t.label(ctx, 16f, t.semibold)
    private val notice = t.label(ctx, 15f).apply { maxLines = 2 }
    /** The small window a held Center opens (a channel in the guide, a group in the column); one at a time. */
    private val popup = HoldPopup(ctx, t)
    /** The channel the small window is for, as it stands (its star follows each press). */
    private var popupChannel: Channel? = null
    /** Where the selection goes when the small window closes: the held cell, or the group's row (or the one in its place). */
    private var popupFocusBack: (() -> Unit)? = null

    private val logos = LogoCache(t.dp(76)) { url, loaded ->
        if (loaded) {
            guide.invalidate()
            refreshControls()
        }
        // The focused channel's own: its logo shows on the plate waiting for it, or (it failed) the name moves over.
        detailChannelObj?.let { if (it.logo == url) showDetailLogo(it) }
    }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerGuide").apply { priority = Thread.NORM_PRIORITY - 1 } }
    /**
     * The viewer's own changes (favorites, groups) are stored here, not queued behind the guide's reads: the queue the
     * settings windows' stars go through too, so Assign favorites opened from here sees a star set a moment before.
     */
    private val writes get() = LiveDb.writes

    private var playlists: List<Playlist> = emptyList()
    /** Group names by (playlist, group), for the details' "Playlist • Group" line. */
    private var groupNames: Map<Pair<String, String>, String> = emptyMap()
    private var scope: GuideScope = prefs.scope ?: GuideScope.AllPlaylists
    private var fullScreen = false
    private var sidebarOpen = true
    private var started = false
    private var backLongPressed = false
    private var focusBeforeFull: ChannelKey? = null
    private var zappedInFull = false
    private var detailCell: Cell? = null
    private var detailChannelObj: Channel? = null
    private var loadSerial = 0

    // Geometry (dp on the 960 x 540 dp screen), after the user's concepts: no panels; a large miniature with the details
    // beside it; the guide below, full width in the grid. With the groups column open the tab rail's strip shows at the
    // left, then the column, then the content; in the grid both slide away and the content slides left (one motion).
    // Open: rail 58 + column 224 + gap 12 + miniature 368 + 18 + details 268 + margin 12 = 960 ([LiveWidths]).
    private val railSpace = t.dp(LiveWidths.RAIL)
    private val columnW = t.dp(LiveWidths.COLUMN)
    private val miniW = t.dp(LiveWidths.MINI)
    private val miniH = t.dp(207)
    private val gap = t.dp(LiveWidths.GAP)
    /**
     * The details' logo tile ([LiveWidths]: large, the logo fitted inside with its shape kept), and the room before the
     * name when there's one.
     */
    private val detailLogoW = t.dp(LiveWidths.DETAIL_LOGO_W)
    private val detailLogoH = t.dp(LiveWidths.DETAIL_LOGO_H)
    private val detailNameInset = detailLogoW + t.dp(LiveWidths.DETAIL_NAME_GAP)
    private val openInset = railSpace + columnW + gap
    private val closedInset = gap

    /**
     * How far the content sits right of its place in the grid while the column is open. The content is laid out once,
     * at the grid's place, and slides by this much as a whole (a transform: nothing is laid out again frame by frame).
     */
    private val shift = openInset - closedInset
    /** True while the content, column and rail slide: the miniature is already on its way to where the slide ends. */
    private var sliding = false
    /** Bumped by every change of layout or screen: a motion waiting for its first frame starts only if still current. */
    private var motionSerial = 0

    init {
        build()
        wire()
    }

    // ---- Layout ---------------------------------------------------------------------------------------------------

    private fun build() {
        // Column: playlists and groups, from the top of the screen.
        sidebarPanel.addView(sidebar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sidebarPanel.addView(columnRule, FrameLayout.LayoutParams(t.dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        guideLayer.addView(sidebarPanel, FrameLayout.LayoutParams(columnW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = railSpace; topMargin = t.dp(8); bottomMargin = t.dp(8)
        })

        // Content: the miniature and its details on top, the guide under them.
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(videoSlot, LinearLayout.LayoutParams(miniW, miniH))
        // The top line: [logo] Channel name … star. Its height is the logo's, logo or not, so the lines under it never
        // move; a channel without a logo has its name moved over to the start by a transform ([showDetailLogo]).
        val head = FrameLayout(ctx)
        detailLogo.pad(LiveWidths.DETAIL_LOGO_PAD_X, LiveWidths.DETAIL_LOGO_PAD_Y)
        head.addView(detailLogo, FrameLayout.LayoutParams(detailLogoW, detailLogoH, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        // Beside the logo, centred on it; a long name takes a second line there. As tall as the line whatever it says,
        // so a new channel's name only redraws it.
        detailChannel.maxLines = 2
        detailChannel.gravity = Gravity.CENTER_VERTICAL
        head.addView(detailChannel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, detailLogoH).apply {
            leftMargin = detailNameInset; rightMargin = t.dp(LiveWidths.DETAIL_STAR)
        })
        detailStar.imageTintList = android.content.res.ColorStateList.valueOf(t.text)
        head.addView(detailStar, FrameLayout.LayoutParams(t.dp(22), t.dp(22), Gravity.END or Gravity.CENTER_VERTICAL))
        details.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, detailLogoH))
        // Full width, one line each: a new channel's text only redraws them (nothing is laid out again as focus moves).
        detailTitle.maxLines = 1
        detailTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        details.addView(detailTitle, wide(top = LiveWidths.DETAIL_TITLE_GAP))
        detailMeta.maxLines = 1
        detailMeta.ellipsize = android.text.TextUtils.TruncateAt.END
        detailMeta.alpha = 0.85f
        details.addView(detailMeta, wide(top = 2))
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailTime)
            // Each has its own space before it: with the bar hidden (a programme not on now) the time and "in …" keep theirs.
            addView(detailBar, LinearLayout.LayoutParams(t.dp(96), t.dp(10)).apply { leftMargin = t.dp(12) })
            addView(detailLeft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(12) })
        }
        details.addView(timeRow, lp(top = 8))
        details.addView(detailDesc, wide(top = 8))
        details.addView(detailNext, wide(top = 6))
        topRow.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = t.dp(LiveWidths.DETAILS_GAP) })
        content.addView(topRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, miniH))
        content.addView(guide, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = miniH + t.dp(10) })
        // Down to the screen's bottom edge: the guide fits exactly its eight whole rows in there ([GuideView.ROWS]).
        guideLayer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = closedInset; topMargin = t.dp(14); rightMargin = gap
        })
        content.translationX = shift.toFloat()
        root.addView(guideLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Nothing to show yet: a way to add a playlist.
        emptyButton.apply {
            text = "Add a playlist"
            isFocusable = true
            setPadding(t.dp(22), t.dp(10), t.dp(22), t.dp(10))
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(t.accent, t.text)))
            Glass.background(this, Glass.Kind.PILL)
            setOnClickListener { host.openPlaylists() }
        }
        empty.addView(emptyText)
        empty.addView(emptyButton, lp(top = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = railSpace })
        empty.visibility = View.GONE

        notice.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10))
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = t.dp(30) })
        notice.visibility = View.GONE
        root.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        popup.onClose = { focusBack ->
            val back = popupFocusBack
            popupFocusBack = null
            popupChannel = null
            if (focusBack) back?.invoke()
            // The viewer was busy in it: the guide's wait for full screen starts again.
            scheduleIdle()
        }
        applyLook()
    }

    // ---- The column and the rail: open (column focused) or in the grid -------------------------------------------

    /**
     * The details' and the guide's right edges: [shift] in from the content's (narrow, so they end at the screen's
     * margin while the content sits [shift] to the right) or none (the grid). A layout pass when they change: once per
     * slide, never during one.
     */
    private fun narrowDetails(on: Boolean) {
        rightMargin(details, if (on) shift else 0)
        // Narrow, the progress bar is shorter, so the time and "… min left" still fit beside it.
        val bar = detailBar.layoutParams as LinearLayout.LayoutParams
        val w = t.dp(if (on) 44 else 96)
        val m = t.dp(if (on) 8 else 12)
        if (bar.width != w) {
            bar.width = w
            bar.leftMargin = m
            detailBar.layoutParams = bar
            val left = detailLeft.layoutParams as LinearLayout.LayoutParams
            left.leftMargin = m
            detailLeft.layoutParams = left
        }
    }
    private fun narrowGuide(on: Boolean) = rightMargin(guide, if (on) shift else 0)

    private fun rightMargin(v: View, px: Int) {
        val lp = v.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.rightMargin == px) return
        lp.rightMargin = px
        v.layoutParams = lp
    }

    /**
     * Column open (with the tab rail) or the grid (both slid away, content full width). One motion: the column and rail
     * slide out to the left as the content slides left, and the miniature moves with it.
     *
     * The content slides as a whole (its translation), so nothing is laid out frame by frame. The details take their
     * open width when the column starts to open and their grid width when it has closed, so their text never runs
     * off the screen; the guide is the grid's width while it moves (running past the screen's edge, which hides it) and
     * ends at the screen's margin once open.
     */
    private fun layoutFor(columnOpen: Boolean, animate: Boolean) {
        val target = if (columnOpen) shift.toFloat() else 0f
        val serial = ++motionSerial
        content.animate().cancel()
        sidebarPanel.animate().cancel()
        sliding = false
        val hidden = -(railSpace + columnW).toFloat()
        if (columnOpen) sidebarPanel.visibility = View.VISIBLE
        if (!animate || !root.isShown) {
            host.setRailShown(columnOpen, 0L)
            content.translationX = target
            narrowDetails(columnOpen)
            narrowGuide(columnOpen)
            // In the grid the channel names get more room.
            guide.setWideColumn(!columnOpen)
            sidebarPanel.translationX = if (columnOpen) 0f else hidden
            sidebarPanel.alpha = if (columnOpen) 1f else 0f
            if (!columnOpen) sidebarPanel.visibility = View.INVISIBLE
            return
        }
        if (columnOpen) narrowDetails(true) else narrowGuide(false)
        sliding = true
        onNextFrame {
            if (serial != motionSerial || released) return@onNextFrame
            slide(columnOpen, target, hidden)
        }
    }

    /**
     * Runs [action] in the next frame, before it's drawn. A motion started there begins with that frame, after the
     * slow one this frame may be (a layout, focus moving, something drawn for the first time): it then plays from its
     * start instead of jumping ahead over the slow frame (on a Fire TV, up to a third of a slide).
     */
    private fun onNextFrame(action: () -> Unit) = android.view.Choreographer.getInstance().postFrameCallback { action() }

    private fun slide(columnOpen: Boolean, target: Float, hidden: Float) {
        host.setRailShown(columnOpen, SLIDE_MS)
        // The miniature heads for where the slot will be when the slide ends, in step with it (same time and easing).
        placeMini(SLIDE_MS, dx = target - content.translationX)
        content.animate().translationX(target).setDuration(SLIDE_MS).setInterpolator(EASE)
            .withEndAction {
                sliding = false
                if (columnOpen) narrowGuide(true) else narrowDetails(false)
                // Exactly in place (a no-op unless something moved the slot meanwhile).
                if (!fullScreen && rectOf(videoSlot) != placedAt) placeMini()
            }
            .start()
        // The column fades as one picture (a layer drawn once), not drawn again into a new layer every frame.
        sidebarPanel.animate().translationX(if (columnOpen) 0f else hidden).alpha(if (columnOpen) 1f else 0f)
            .setDuration(SLIDE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!sidebarOpen) sidebarPanel.visibility = View.INVISIBLE }
            .start()
        // The channel names' column widens in the grid (narrows as the column comes back), in step: drawn, not laid out.
        guide.setWideColumn(!columnOpen, SLIDE_MS, EASE)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(top) }
    private fun wide(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(top) }

    /** Panels get the look's surface (glass over the blurred wallpaper, or the original dark panel). */
    fun applyLook() {
        // The guide's glass blurs the wallpaper and is never dimmed: it's made with the film off, even when the look
        // changes while the full-screen controls are up; the controls are then made again over the film.
        val film = filmGlassOn
        if (film) host.setFilmGlass(false)
        // No panels (user's concepts): the column, details and guide sit on the wallpaper.
        sidebarPanel.background = null
        details.background = null
        // Glass: the column's edge is the shared hairline (about a pixel on the TV), as the guide's rules are.
        columnRule.setBackgroundColor(if (Glass.on) GlassStyle.HAIRLINE else 0x14FFFFFF)
        val ruleW = if (Glass.on) GlassStyle.hairlinePx(t.density).toInt().coerceAtLeast(1) else t.dp(1)
        columnRule.layoutParams?.let { if (it.width != ruleW) { it.width = ruleW; columnRule.requestLayout() } }
        Glass.overlay(notice, Glass.Kind.POPUP)
        Glass.background(emptyButton, Glass.Kind.PILL)
        popup.applyLook()
        guide.glass = Glass.on
        // Its tile is the look's (glass tile or classic plate): drawn again.
        detailLogo.invalidate()
        if (film) host.setFilmGlass(true)
        controls.applyLook()
        if (placeholder == true) { placeholder = null; showSlotPlaceholder(true) }
    }

    private fun wire() {
        // The slot moves when the column opens or closes (its parent moves, not the slot within it): every layout pass
        // compares where it is on screen with where the video was put.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            // Mid-slide the slot is on its way; the miniature already heads for where it ends.
            if (fullScreen || sliding || !root.isShown) return@addOnGlobalLayoutListener
            if (player.currentKey == null) {
                if (placeholder != true) placeMini()
                return@addOnGlobalLayoutListener
            }
            val r = rectOf(videoSlot) ?: return@addOnGlobalLayoutListener
            if (shrinkPending) {
                // Back from full screen with the content just moved: shrink into where the miniature is now.
                shrinkPending = false
                placeMini(SHRINK_MS)
            } else if (r != placedAt) placeMini()
        }
        guide.callbacks = object : GuideView.Callbacks {
            override fun onFocusMoved(channel: Channel, cell: Cell?) = showDetails(channel, cell)
            override fun onPick(channel: Channel, cell: Cell?) = pick(channel)
            override fun onHold(channel: Channel) = openChannelPopup(channel)
            override fun onLeaveLeft() = openSidebar(focus = true)
            override fun needProgrammes(channels: List<Channel>, from: Long, to: Long) = loadProgrammes(channels, from, to)
            override fun logo(url: String): Logo? = logos.get(url)
        }
        guide.setOnFocusChangeListener { _, has -> if (has) closeSidebar() }
        sidebar.callbacks = object : GuideSidebar.Callbacks {
            override fun onScope(scope: GuideScope, enter: Boolean) {
                if (scope != this@LivePage.scope) {
                    this@LivePage.scope = scope
                    prefs.scope = scope
                    loadChannels(focus = null, enterGrid = enter)
                } else if (enter) {
                    // The same scope again. Already shown (no load on its way): straight in; reloading it first took
                    // seconds with a big playlist. Otherwise go in once its list has arrived, if it has channels.
                    when {
                        // Its list is on its way: go in when it arrives (a second read would only start over).
                        loadingScope == scope -> pendingEnter = true
                        shownScope == scope && guide.channelList().isNotEmpty() -> guide.requestFocus()
                        else -> loadChannels(focus = if (shownScope == scope) guide.focusedChannel()?.key else null, enterGrid = true)
                    }
                }
            }

            override fun onHold(scope: GuideScope.Group, name: String, row: View) = openGroupPopup(scope, name, row)
        }
        controls.actions = object : LiveControls.Actions {
            override fun onGuide() = toGuide(toPlaying = true)
            override fun onHistory() = showHistory()
            override fun onCaptions(from: View) = chooseCaptions(from)
            override fun onFavorite() { toggleFavorite() }
            override fun onPlayPause() = togglePause()
            override fun onGoLive() = goLive()
            override fun onMore(key: String, from: View) {
                if (key == LiveControls.SETTINGS) host.openPlayerSettings { backFromWindow(from) } else more.open(key, from)
            }
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    private val statusListener = object : PlayerHost.Listener {
        override fun onLiveStatus(status: PlayerHost.Status) {
            guide.playingKey = ChannelKey.parse(status.key)
            controls.setStatus(status)
            status.error?.let { showNotice(it) }
            val pausedNow = status.paused
            if (pausedNow != wasPaused) {
                wasPaused = pausedNow
                pauseChanged(pausedNow)
            }
        }
    }

    /** Whether the channel was paused at the last status, to see a pause or resume come. */
    private var wasPaused = false

    /**
     * In full screen, a pause the viewer didn't make (another app took the sound) shows the controls with Play
     * selected, as the viewer's own pause does, rather than leaving a still picture with nothing to say so; carrying
     * on gives the controls their usual time again.
     */
    private fun pauseChanged(paused: Boolean) {
        if (!fullScreen || released) return
        if (paused) {
            if (!controls.controlsShown && !FireDialog.isShowing(activity) && !host.overlayOpen()) {
                showControls()
                controls.focusPlay()
            }
        } else if (controls.controlsShown) {
            bumpControls()
        }
    }

    private val dataListener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) {
            // A change this page made itself (a group moved or hidden) is on screen already: reading everything again
            // would redraw the column a second time and empty the guide's rows while their listings load.
            if (ownChanges > 0) { ownChanges--; return }
            reload(keepFocus = true)
        }
    }

    /** Changes this page announced ([announce]) that its own listener is still to hear of. */
    private var ownChanges = 0

    /** When the tab last started showing (uptime ms), until its guide is listed: for the log of how long that took. */
    private var openedAt = 0L
    /** When a key last went down here (KeyEvent.eventTime), for the log of how long a channel took to show. */
    private var keyAt = 0L

    /** The tab is showing (activity started, or switched to this tab). */
    fun start() {
        if (started) return
        started = true
        // Everything is read again below: an announcement heard of while away is no longer to be skipped.
        ownChanges = 0
        player.addListener(statusListener)
        sync.addListener(dataListener)
        // A channel that was playing comes back in the miniature (not full screen).
        openedAt = android.os.SystemClock.uptimeMillis()
        startedAt = openedAt
        if (!player.onScreenStarted() && player.currentKey == null) {
            prefs.lastChannel?.let { key ->
                // Built now, while the channel is read, rather than when it arrives.
                player.warm()
                resumeLast(key)
            }
        }
        // The rail and column as they were (the rail shows with the column, or while there's nothing else).
        layoutFor(columnOpen = sidebarOpen || playlists.isEmpty(), animate = false)
        reload(keepFocus = true)
        scheduleIdle()
        main.post(clockTick)
        // A sleep timer that waited while the page was away carries on.
        more.resume()
    }

    /** The tab is hidden or the activity stopped: the stream is released, the channel remembered. */
    fun stop() {
        if (!started) return
        started = false
        // The selection goes back to the held row (or cell) first, quietly: a window gone while it still held focus
        // would hand it to the column's top row, whose preview would then switch the guide.
        popup.close(fade = false)
        backClosedPopup = false
        dropZap()
        player.removeListener(statusListener)
        sync.removeListener(dataListener)
        player.onScreenStopped(host.videoOwner)
        placedAt = null
        main.removeCallbacks(idleToFull)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(hideControls)
        main.removeCallbacks(hideNotice)
        more.pause()
        // The controls' timer is gone: don't leave them up for the return.
        if (fullScreen) controls.hideAll()
        filmGlass(false)
        revealKey = 0
        backDownSeen = false
    }

    /**
     * Film mode (design-brief.md §6.6), while the tab is stopped: the guide's listings and the channel logos leave
     * memory; they load again as they show.
     */
    fun dropMemory() {
        if (started) return
        guide.forgetProgrammes()
        logos.clear()
    }

    fun release() {
        stop()
        more.release()
        released = true
        ++reloadSerial
        ++loadSerial
        io.shutdown()
        // Queued favorites are still stored (the queue is the app's, not this page's).
    }

    /** Background work, unless the page is gone (its executor is shut down then). Main thread only. */
    private fun bg(task: () -> Unit) {
        if (!released) io.execute(task)
    }

    private var released = false
    private var loaded = false
    private var focusPending = false

    /** Where focus goes when this tab is entered from the rail (once its data has loaded). */
    fun takeFocus() {
        if (!loaded) { focusPending = true; return }
        focusPending = false
        if (playlists.isEmpty()) { if (empty.visibility == View.VISIBLE) emptyButton.requestFocus(); return }
        if (sidebarOpen) sidebar.focusCurrent() else guide.requestFocus()
    }

    /** Any key while this tab shows: the guide's inactivity timer starts again. */
    fun noteKey() {
        if (started && !fullScreen) scheduleIdle()
    }

    // ---- Data -----------------------------------------------------------------------------------------------------

    private var reloadSerial = 0

    private fun reload(keepFocus: Boolean) {
        val serial = ++reloadSerial
        bg {
            val pls = db.playlists()
            val groups = pls.associate { it.id to db.groups(it.id) }
            // A guide counts as loading until its first import was attempted (worked or not).
            val withGuide = db.epgStatuses().map { it.source }.toSet()
            main.post {
                if (serial != reloadSerial || released) return@post
                playlists = pls
                // A first guide import can take minutes on a big provider: until it lands, empty rows say so.
                guide.loadingPlaylists = if (sync.busy) pls.map { it.id }.filter { it !in withGuide }.toSet() else emptySet()
                val first = !loaded
                loaded = true
                showEmpty(pls.isEmpty())
                if (pls.isNotEmpty()) {
                    if (!scopeValid(scope, pls, groups)) scope = GuideScope.AllPlaylists
                    sidebar.setData(pls, groups, scope)
                    groupNames = groups.values.flatten().associate { (it.playlistId to it.id) to it.name }
                    loadChannels(focus = if (keepFocus) guide.focusedChannel()?.key ?: prefs.focus else null, enterGrid = false)
                }
                // The first load: focus wasn't placed yet (the page had nothing to focus).
                if (focusPending || (first && !fullScreen && root.isShown && activity.currentFocus.let { it == null || !isInPage(it) && !host.railHasFocus() })) takeFocus()
            }
        }
    }

    private fun scopeValid(s: GuideScope, pls: List<Playlist>, groups: Map<String, List<ChannelGroup>>): Boolean = when (s) {
        GuideScope.AllPlaylists -> true
        is GuideScope.AllChannels -> pls.any { it.id == s.playlistId }
        is GuideScope.Favorites -> pls.any { it.id == s.playlistId }
        is GuideScope.Group -> groups[s.playlistId].orEmpty().any { it.id == s.groupId }
    }

    /** The scope whose channels the guide shows, and the one being loaded (null when none is on its way). */
    private var shownScope: GuideScope? = null
    private var loadingScope: GuideScope? = null
    /** OK/Right came while [loadingScope]'s list was loading: the grid is entered when it arrives. */
    private var pendingEnter = false

    private fun loadChannels(focus: ChannelKey?, enterGrid: Boolean) {
        val s = scope
        val serial = ++loadSerial
        if (loadingScope != s) pendingEnter = false
        loadingScope = s
        bg {
            val list = db.channels(s)
            main.post {
                if (serial != loadSerial || released) return@post
                showChannels(s, list, focus, enterGrid)
                if (openedAt != 0L) {
                    Log.i(TAG, "Live TV: guide listed (${list.size} channels) ${android.os.SystemClock.uptimeMillis() - openedAt} ms after opening")
                    openedAt = 0L
                }
            }
        }
    }

    /** [s]'s [list] arrived: the guide shows it, focus on [focus] (else the channel playing, else the last focused). */
    private fun showChannels(s: GuideScope, list: List<Channel>, focus: ChannelKey?, enterGrid: Boolean) {
        shownScope = s
        loadingScope = null
        guide.clearProgrammes()
        guide.setChannels(list, focus ?: ChannelKey.parse(player.currentKey) ?: prefs.focus)
        if (list.isEmpty()) {
            showDetailsEmpty(if (s is GuideScope.Favorites) "No favorites yet. Add one with the Favorite button in full screen." else
                if (sync.busy) "Updating the playlist…" else "No channels here.")
        }
        if ((enterGrid || pendingEnter) && list.isNotEmpty()) guide.requestFocus()
        pendingEnter = false
    }

    private fun loadProgrammes(channels: List<Channel>, from: Long, to: Long) {
        bg {
            val map = db.programmes(channels, from, to)
            main.post { if (!released) guide.putProgrammes(channels, map, from, to) }
        }
    }

    private fun isInPage(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === root) return true; p = p.parent as? View }
        return false
    }

    private fun showEmpty(noPlaylists: Boolean) {
        if (noPlaylists && fullScreen) {
            // The last playlist went (deleted from Settings over full screen): leave full screen for the empty page.
            fullScreen = false
            revealKey = 0
            controls.hideAll()
            filmGlass(false)
            host.setChromeVisible(true)
            guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            guideLayer.animate().cancel()
            guideLayer.alpha = 1f
            guide.setChannels(emptyList(), null)
        }
        // The tab rail stays reachable while there's nothing else to go to.
        if (noPlaylists) { sidebarOpen = true; layoutFor(columnOpen = true, animate = false) }
        empty.visibility = if (noPlaylists) View.VISIBLE else View.GONE
        guideLayer.visibility = if (noPlaylists || fullScreen) View.INVISIBLE else View.VISIBLE
        if (noPlaylists) {
            emptyText.text = "Add a playlist to watch Live TV"
            host.placeVideo(null, 0f, false)
        } else if (!fullScreen) {
            placeMini()
        }
    }

    // ---- Guide ----------------------------------------------------------------------------------------------------

    private fun showDetails(channel: Channel, cell: Cell?) {
        detailChannelObj = channel
        detailCell = cell
        prefs.focus = channel.key
        val p = cell?.programme
        val tf = DateFormat.getTimeFormat(ctx)
        // Channel (its logo first), then its title; under it where the channel is: "Playlist • Group" (the concepts).
        showDetailLogo(channel)
        detailChannel.text = channel.name
        detailStar.setImageResource(if (channel.favorite) app.fireplayer.R.drawable.ic_star_filled else app.fireplayer.R.drawable.ic_star)
        detailStar.alpha = if (channel.favorite) 1f else 0.7f
        detailStar.contentDescription = if (channel.favorite) "In your favorites" else "Not in your favorites"
        detailStar.visibility = View.VISIBLE
        val playlistName = playlists.firstOrNull { it.id == channel.playlistId }?.name
        val groupName = channel.groupId?.let { groupNames[channel.playlistId to it] }
        detailMeta.text = listOfNotNull(playlistName, groupName).joinToString("  •  ")
        if (p == null) {
            detailTitle.text = channel.name
            detailTime.text = "No programme information"
            detailBar.visibility = View.GONE
            detailLeft.text = ""
            detailDesc.text = ""
            detailNext.text = ""
            return
        }
        val now = System.currentTimeMillis()
        detailTitle.text = p.title.ifBlank { "Untitled" }
        detailTime.text = "${tf.format(Date(p.start))} – ${tf.format(Date(p.stop))}"
        val onAir = p.isOn(now)
        detailBar.visibility = if (onAir) View.VISIBLE else View.GONE
        detailBar.fraction = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
        detailLeft.text = when {
            onAir -> "${(p.stop - now) / 60_000} min left"
            p.start > now -> "in ${formatDelay(p.start - now)}"
            else -> "Ended"
        }
        detailDesc.text = p.description ?: ""
        // What's next only when there's no story to tell (the guide shows it beside this one anyway).
        val next = if (p.description.isNullOrBlank()) nextAfter(channel, p.stop) else null
        detailNext.text = next?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
    }

    private fun showDetailsEmpty(text: String) {
        // Nothing focused: the clock's refresh mustn't bring back the previous scope's channel.
        detailChannelObj = null
        detailCell = null
        showDetailLogo(null)
        detailChannel.text = ""
        detailStar.visibility = View.INVISIBLE
        detailMeta.text = ""
        detailTitle.text = text
        detailTime.text = ""
        detailBar.visibility = View.GONE
        detailLeft.text = ""
        detailDesc.text = ""
        detailNext.text = ""
    }

    /**
     * [channel]'s logo before its name on the details' top line: the guide's, on the same plate; a logo still on its
     * way shows its plate already, so the name doesn't move when it lands. No logo: the name alone at the line's
     * start, moved there by a transform (the line keeps its height and nothing is laid out again as focus moves).
     */
    private fun showDetailLogo(channel: Channel?) {
        val url = channel?.logo
        val logo = url?.let { logos.get(it) }
        val plate = logo != null || (url != null && !logos.missing(url))
        detailLogo.set(logo, plate)
        detailChannel.translationX = if (plate) 0f else -detailNameInset.toFloat()
    }

    private fun formatDelay(ms: Long): String {
        val min = ms / 60_000
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun nextAfter(channel: Channel, at: Long): Programme? = guide.programmesOf(channel).firstOrNull { it.start >= at }

    private fun pick(channel: Channel) {
        // The playing channel goes full screen; one that failed (or anything else) is tuned (again).
        if (channel.key.toString() == player.playingKey && player.status().error == null) {
            toFull()
        } else {
            tune(channel)
        }
    }

    /**
     * Plays [channel]. [pressedAt] is when the key that asked for it went down (for the log of how long it took to
     * show): the last key here unless it's given, when that was a moment ago.
     */
    private fun tune(channel: Channel, known: Playlist? = null, pressedAt: Long = recentKey()): Boolean {
        // Whatever a held key was heading for is overtaken.
        main.removeCallbacks(tunePending)
        zapPending = null
        val playlist = known ?: playlists.firstOrNull { it.id == channel.playlistId } ?: return false
        val result = player.tune(LiveSession.Source(channel.key.toString(), channel.url, playlist.id, playlist.maxConnections), pressedAt)
        if (result == LiveSession.Tune.NoConnection) {
            showNotice("This playlist's connection limit is reached")
            return false
        }
        prefs.lastChannel = channel.key
        playingChannel = channel
        // A channel change from elsewhere closes a small window still open.
        popup.close()
        bg { db.noteRecent(channel.key, System.currentTimeMillis()) }
        if (!fullScreen) placeMini()
        return true
    }

    /**
     * The channel last watched plays again in the miniature when Live TV opens with nothing playing (provisional:
     * like TiviMate's "turn on last channel on app start", on by default here). Its playlist is read here too:
     * the page's own list may not have loaded yet.
     */
    /** The channel playing, kept here: it may not be in the scope the guide shows. */
    private var playingChannel: Channel? = null

    /** The last key's time when it went down within the last couple of seconds, else 0 (nothing was pressed for this). */
    private fun recentKey(): Long = keyAt.takeIf { it > 0 && android.os.SystemClock.uptimeMillis() - it < 2_000 } ?: 0L

    private fun resumeLast(key: ChannelKey) {
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            // Timed from the tab's opening: that's the wait the viewer sees.
            main.post { if (started && player.currentKey == null) tune(ch, pl, pressedAt = openedAtForTune()) }
        }
    }

    /**
     * A channel picked outside this tab (Home's Recent channels): it is the channel watched last from now on, so the
     * tab's start plays it in the miniature; while the tab shows, it plays now unless it's playing already.
     */
    fun openChannel(key: ChannelKey) {
        prefs.lastChannel = key
        if (!started) return
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            main.post { if (started && !released && player.currentKey != key.toString()) tune(ch, pl, pressedAt = openedAtForTune()) }
        }
    }

    private var startedAt = 0L
    private fun openedAtForTune(): Long = startedAt.takeIf { android.os.SystemClock.uptimeMillis() - it < 10_000 } ?: 0L

    private fun openSidebar(focus: Boolean) {
        val was = sidebarOpen
        sidebarOpen = true
        if (!was || sidebarPanel.visibility != View.VISIBLE) layoutFor(columnOpen = true, animate = true)
        if (focus) sidebar.focusCurrent()
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        layoutFor(columnOpen = false, animate = true)
    }

    /** The miniature in its slot; with [dx], where the slot will be once the content has slid that much further. */
    private fun placeMini(animateMs: Long = 0L, dx: Float = 0f) {
        val playing = player.currentKey != null
        if (playlists.isEmpty() || !playing) {
            placedAt = null
            host.placeVideo(null, 0f, false)
            showSlotPlaceholder(true)
            return
        }
        val r = rectOf(videoSlot) ?: return
        r.offset(dx, 0f)
        placedAt = r
        showSlotPlaceholder(false)
        host.placeVideo(r, t.dpf(12f), false, animateMs)
    }

    /** Whether the miniature's slot shows its placeholder panel (null: not decided yet, or the look changed). */
    private var placeholder: Boolean? = null

    private fun showSlotPlaceholder(on: Boolean) {
        if (placeholder == on) return
        placeholder = on
        if (on) Glass.background(videoSlot, Glass.Kind.PANEL) else videoSlot.background = null
        videoSlot.setTextColor(if (on) t.textMuted else 0)
    }

    private fun rectOf(v: View): RectF? {
        if (v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val base = IntArray(2)
        (root.rootView).getLocationInWindow(base)
        val x = (loc[0] - base[0]).toFloat()
        val y = (loc[1] - base[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    // ---- Full screen ----------------------------------------------------------------------------------------------

    fun toFull() {
        if (player.playingKey == null || fullScreen) return
        popup.close(fade = false, focusBack = false)
        fullScreen = true
        revealKey = 0
        focusBeforeFull = guide.focusedChannel()?.key
        zappedInFull = false
        main.removeCallbacks(idleToFull)
        // Paused in the guide: full screen opens with the controls up and Play selected (they stay while paused), so a
        // still picture always says it's paused.
        val paused = player.paused
        // Nothing in the hidden guide may keep or take focus: in full screen the keys come to dispatchKey.
        guideLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        activity.currentFocus?.clearFocus()
        guideLayer.animate().cancel()
        host.setChromeVisible(false)
        placedAt = null
        refreshControls()
        // The channel and programme fade in as the picture nears full screen (not over the guide fading away).
        if (!paused) controls.showInfo(fadeInAfter = GROW_MS / 2)
        // The picture grows from the miniature to the whole screen while the guide fades away under it, from the next
        // frame (see onNextFrame). The guide fades as one picture (a layer drawn once), not drawn again into a new
        // screen-sized layer every frame.
        val serial = ++motionSerial
        onNextFrame {
            if (serial != motionSerial || !fullScreen || released) return@onNextFrame
            guideLayer.animate().alpha(0f).setDuration(GROW_MS * 3 / 4).setInterpolator(EASE).withLayer()
                .withEndAction { if (fullScreen) guideLayer.visibility = View.INVISIBLE }.start()
            host.placeVideo(null, 0f, true, GROW_MS)
        }
        main.removeCallbacks(hideControls)
        if (paused) {
            showControls()
            controls.focusPlay()
        } else {
            main.postDelayed(hideControls, INFO_MS)
        }
    }

    /**
     * Back to the guide from full screen. [toPlaying] (TV Guide): focus on the channel playing, on what's on now
     * ([focusPlaying]); otherwise (Back) where the guide was, unless the channel changed in full screen.
     */
    fun toGuide(toPlaying: Boolean = false) {
        if (!fullScreen) return
        dropZap()
        fullScreen = false
        revealKey = 0
        controls.hideAll()
        filmGlass(false)
        host.setChromeVisible(true)
        // Back to the grid: the column and rail stay away; the picture shrinks into the miniature as the guide fades in.
        sidebarOpen = false
        layoutFor(columnOpen = false, animate = false)
        guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        guideLayer.animate().cancel()
        guideLayer.visibility = View.VISIBLE
        // The guide fades in and the picture shrinks into the miniature from the next frame (see onNextFrame). The
        // slot's place is known then (the content only slid back, a transform), unless the page hasn't been laid out
        // yet: then the shrink waits for the layout.
        // (Back in full screen by then: that leaves the guide hidden. A slide started meanwhile places the picture
        // again itself.)
        onNextFrame {
            if (fullScreen || released) return@onNextFrame
            guideLayer.animate().alpha(1f).setDuration(SHRINK_MS).setInterpolator(EASE).withLayer().start()
            if (rectOf(videoSlot) == null) shrinkPending = true else placeMini(SHRINK_MS)
        }
        // TV Guide: on the channel playing. Back (or the playing channel is in another list, which takes a moment to
        // read): where the guide was, unless the channel changed in full screen: then to that channel.
        if (!(toPlaying && focusPlaying())) {
            val playing = ChannelKey.parse(player.currentKey)
            if (zappedInFull && playing != null) guide.focusChannel(playing)
            else focusBeforeFull?.let { k -> if (guide.focusedChannel()?.key != k) guide.focusChannel(k) }
        }
        guide.requestFocus()
        scheduleIdle()
    }

    /**
     * The guide's focus on the channel playing, on the programme on air (TV Guide in full screen); true when it's
     * there now. When the list the guide shows hasn't got it (a channel from Channel History, say), the guide shows its
     * group instead, else its playlist's All channels, with it focused there, once read; the groups column marks that
     * entry and stays as it was. A list the viewer asks for meanwhile wins (one asked for before this doesn't).
     */
    private fun focusPlaying(): Boolean {
        val key = ChannelKey.parse(player.currentKey) ?: return false
        if (guide.channelList().any { it.key == key }) { guide.focusChannel(key); return true }
        val known = playingChannel?.takeIf { it.key == key }
        // The groups the column lists (a hidden group isn't one to show).
        val shown = groupNames.keys
        val serial = loadSerial
        bg {
            val ch = known ?: db.channel(key) ?: return@bg
            val group = ch.groupId?.takeIf { (ch.playlistId to it) in shown }?.let { GuideScope.Group(ch.playlistId, it) }
            for (s in listOfNotNull(group, GuideScope.AllChannels(ch.playlistId))) {
                val list = db.channels(s)
                if (list.none { it.key == key }) continue
                main.post {
                    if (serial != loadSerial || released || fullScreen || player.currentKey != key.toString()) return@post
                    // Any list still on its way is older than this: it's dropped.
                    ++loadSerial
                    scope = s
                    prefs.scope = s
                    sidebar.select(s)
                    showChannels(s, list, key, enterGrid = false)
                    guide.focusChannel(key)
                }
                return@bg
            }
        }
        return false
    }

    private fun refreshControls() {
        // The channel a held key has reached is named at once, before it's opened ([zap]).
        val pending = zapPending
        val key = pending?.key ?: ChannelKey.parse(player.currentKey) ?: return
        // The guide's copy first (fresh favorite state), else the channel as tuned: it may be outside this scope.
        val ch = pending ?: guide.channelList().firstOrNull { it.key == key } ?: playingChannel?.takeIf { it.key == key }
        if (ch == null) {
            bg {
                val found = db.channel(key) ?: return@bg
                main.post { if (!released && player.currentKey == key.toString()) { playingChannel = found; refreshControls() } }
            }
            return
        }
        val now = System.currentTimeMillis()
        val progs = guide.programmesOf(ch).ifEmpty { controlsProgrammes.takeIf { controlsProgrammesFor == ch.key }.orEmpty() }
        if (progs.isEmpty() && controlsProgrammesFor != ch.key) {
            // Not in the guide's loaded rows: read its programmes once for the controls.
            controlsProgrammesFor = ch.key
            bg {
                val list = db.programmes(listOf(ch), now - 2 * 60 * 60_000L, now + 6 * 60 * 60_000L)[ch.key].orEmpty()
                main.post { if (!released && controlsProgrammesFor == ch.key) { controlsProgrammes = list; refreshControls() } }
            }
        }
        val cur = progs.firstOrNull { it.isOn(now) }
        val nxt = progs.firstOrNull { it.start >= (cur?.stop ?: now) }
        val plName = playlists.firstOrNull { it.id == ch.playlistId }?.name ?: ""
        controls.setChannel(ch, cur, nxt, plName, ch.logo?.let { logos.get(it) }, now)
    }

    private var controlsProgrammesFor: ChannelKey? = null
    private var controlsProgrammes: List<Programme> = emptyList()

    /** The channel a held Up/Down (or Channel) key has reached: named at once, opened when the key lets up ([zap]). */
    private var zapPending: Channel? = null

    private val tunePending = Runnable {
        val ch = zapPending ?: return@Runnable
        zapPending = null
        if (fullScreen && started && tune(ch)) zapped(ch) else refreshControls()
    }

    /**
     * The next channel down the guide's list ([delta] +1) or the one before (-1), round at the ends. A press opens it
     * at once. A [held] key runs on through the channels: each is named at once, and only the one it stops on is
     * opened (a stream opened for every channel passed would be thrown away again, and providers count connections).
     */
    private fun zap(delta: Int, held: Boolean = false) {
        val list = guide.channelList()
        if (list.isEmpty()) return
        val from = zapPending?.key ?: ChannelKey.parse(player.currentKey)
        val cur = list.indexOfFirst { it.key == from }
        val next = list[Math.floorMod((if (cur < 0) 0 else cur) + delta, list.size)]
        main.removeCallbacks(tunePending)
        if (held) {
            zapPending = next
            refreshControls()
            showZapInfo()
            main.postDelayed(tunePending, ZAP_SETTLE_MS)
            return
        }
        if (tune(next)) zapped(next)
    }

    private fun zapped(next: Channel) {
        zappedInFull = true
        guide.focusChannel(next.key)
        refreshControls()
        showZapInfo()
    }

    /** The channel's name and programme at the top for a moment (unless the controls are up anyway). */
    private fun showZapInfo() {
        if (controls.controlsShown) return
        controls.showInfo()
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, INFO_MS)
    }

    /** A held key's channel that wasn't opened yet is forgotten (full screen is left, or the tab). */
    private fun dropZap() {
        main.removeCallbacks(tunePending)
        zapPending = null
    }

    /** The hide timer found a dialog or window open over the controls and waited for it. */
    private var waitedForOverlay = false

    private val hideControls: Runnable = Runnable {
        if (!fullScreen) return@Runnable
        // A dialog or the settings window is open over the controls: wait for it rather than pulling focus out.
        if (FireDialog.isShowing(activity) || host.overlayOpen()) {
            waitedForOverlay = true
            main.postDelayed(hideControls, 2_000)
            return@Runnable
        }
        // It has closed since (focus is back on the button it came from): the controls get their full time again.
        if (waitedForOverlay) {
            waitedForOverlay = false
            if (controls.controlsShown) {
                bumpControls()
                return@Runnable
            }
        }
        hideControlsNow()
    }

    private fun hideControlsNow() {
        main.removeCallbacks(hideControls)
        waitedForOverlay = false
        val f = activity.currentFocus
        controls.hideAll()
        filmGlass(false)
        revealKey = 0
        if (f != null && isInside(f, controls)) f.clearFocus()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    /** The key that showed the controls, while it's held: its repeats and release don't also move round them. */
    private var revealKey = 0

    private fun showControls(focus: View? = null) {
        refreshControls()
        more.refreshValues()
        filmGlass(true)
        controls.showControls(focus)
        bumpControls()
    }

    private var filmGlassOn = false

    /** Full-screen controls blur the film behind them (glass look); everywhere else glass blurs the wallpaper. */
    private fun filmGlass(on: Boolean) {
        if (filmGlassOn == on) return
        filmGlassOn = on
        host.setFilmGlass(on)
        if (Glass.on) controls.applyLook()
    }

    private fun bumpControls() {
        main.removeCallbacks(hideControls)
        waitedForOverlay = false
        // Paused, the controls stay up (the picture is still and Play is what's wanted next).
        if (player.paused) return
        val s = prefs.controlsHideSec
        if (s > 0) main.postDelayed(hideControls, s * 1000L)
    }

    /** Play/pause (the remote's key or the Play button): the controls show, with Play selected while paused. */
    private fun togglePause() {
        if (!fullScreen) return
        player.togglePause()
        controls.setStatus(player.status())
        if (!controls.controlsShown) showControls() else bumpControls()
        if (player.paused) controls.focusPlay()
    }

    private fun goLive() {
        player.goLive()
        controls.setStatus(player.status())
        bumpControls()
    }

    private fun showHistory() {
        bg {
            val recent = db.recent().filter { it.key.toString() != player.currentKey }
            main.post {
                if (recent.isEmpty()) { showNotice("No other channels watched yet"); return@post }
                FireDialog.choose(activity, "Channel History", recent.map { "${it.number}  ${it.name}" }, -1) { i ->
                    recent.getOrNull(i)?.let { ch -> if (tune(ch)) { zappedInFull = true; refreshControls(); more.refreshValues() } }
                }
            }
        }
    }

    /** Captions: a side window like Audio's ([LiveMore.openCaptions]); a channel without any says so. */
    private fun chooseCaptions(from: View) {
        if (player.status().captions.isEmpty()) { showNotice("This channel has no captions"); return }
        more.openCaptions(from)
    }

    /**
     * Stars or unstars [target] (the playing channel when null): the full-screen Favorite button, or the star in a
     * channel's small window ([notice] false there: the star filling is the answer). Returns the new state, or null
     * when there's no such channel.
     */
    private fun toggleFavorite(target: Channel? = null, notice: Boolean = true): Boolean? {
        val key = target?.key ?: ChannelKey.parse(player.currentKey) ?: return null
        val ch = guide.channelList().firstOrNull { it.key == key } ?: playingChannel?.takeIf { it.key == key } ?: target ?: return null
        val fav = !ch.favorite
        // Shown at once (the star fills, the guide row and the details follow); stored behind it. The database can be
        // busy for a while with a guide update, which must not hold up the button. Only that row changes in the guide.
        guide.updateChannel(ch.copy(favorite = fav))
        if (key.toString() == player.currentKey) playingChannel = ch.copy(favorite = fav)
        refreshControls()
        if (notice) showNotice(if (fav) "Added to Favorites" else "Removed from Favorites")
        val s = scope
        writes.execute {
            runCatching { db.setFavorite(key, fav, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Favorite not stored: ${it.javaClass.simpleName}") }
            main.post {
                if (released) return@post
                if (s is GuideScope.Favorites && scope == s || loadingScope != null) {
                    // The Favorites list gains or loses the row; a list read before the write is read again. Focus stays
                    // where it was, or on the row next to a removed one.
                    val rows = guide.channelList()
                    val cur = guide.focusedChannel()?.key
                    val i = rows.indexOfFirst { it.key == cur }
                    val keep = if (cur == key && !fav && scope is GuideScope.Favorites) (rows.getOrNull(i + 1) ?: rows.getOrNull(i - 1))?.key else cur
                    loadChannels(focus = keep, enterGrid = false)
                } else {
                    // Already shown; applied again in case a list arrived in between.
                    guide.channelList().firstOrNull { it.key == key }?.let { guide.updateChannel(it.copy(favorite = fav)) }
                    playingChannel = playingChannel?.let { if (it.key == key) it.copy(favorite = fav) else it }
                    refreshControls()
                }
            }
        }
        return fav
    }

    // ---- Small windows (Center held) ------------------------------------------------------------------------------

    /** Center held on [channel] in the guide: its star (selected), its full name and Assign favorites, beside its row. */
    private fun openChannelPopup(channel: Channel) {
        if (fullScreen || popup.isOpen) return
        val row = RectF()
        if (!guide.focusedRowRect(row)) return
        popupChannel = channel
        popupFocusBack = { if (!guide.requestFocus()) takeFocus() }
        // Beside the channel's name, over its programmes, inside the guide.
        val area = inRoot(guide, RectF(0f, 0f, guide.width.toFloat(), guide.height.toFloat()))
        // The guide runs down to the screen's edge: the window keeps a margin from it.
        area.intersect(0f, 0f, root.width.toFloat(), root.height - gap.toFloat())
        popup.open(channel.name, listOf(starButton(channel.favorite), HoldPopup.Button("Assign favorites") { popupAssign() }), inRoot(guide, row), area)
    }

    private fun starButton(favorite: Boolean) = HoldPopup.Button(
        if (favorite) "Remove from Favorites" else "Add to Favorites",
        icon = if (favorite) app.fireplayer.R.drawable.ic_star_filled else app.fireplayer.R.drawable.ic_star,
        selected = favorite,
    ) { popupStar() }

    /** OK on the star: the favorite flips at once (with a small pop), and the window closes by itself a moment later. */
    private fun popupStar() {
        val ch = popupChannel ?: return
        val fav = toggleFavorite(ch, notice = false) ?: return
        popupChannel = ch.copy(favorite = fav)
        popup.update(0, starButton(fav))
        popup.pop(0)
        popup.closeAfter(STAR_CLOSE_MS)
    }

    /** Assign favorites: the window gives way to Assign favorites on this channel's group, the channel selected. */
    private fun popupAssign() {
        val ch = popupChannel ?: return
        // Gone at once (the guide dims under the settings window), focus back on the cell for when that closes.
        popup.close(fade = false)
        host.openAssignFavorites(ch.playlistId, ch.groupId, ch.key)
    }

    /**
     * Center held on a group in the column: its full name, Move to top (selected) and Hide category, beside its row, and
     * Manage groups under them.
     */
    private fun openGroupPopup(scope: GuideScope.Group, name: String, row: View) {
        if (fullScreen || popup.isOpen || row.width == 0) return
        popupFocusBack = { sidebar.focusScope(scope) }
        val m = gap.toFloat()
        val area = RectF(m, m, root.width - m, root.height - m)
        popup.open(name, listOf(
            HoldPopup.Button("Move to top") { moveGroupToTop(scope) },
            HoldPopup.Button("Hide category") { hideGroup(scope) },
        ), inRoot(row, RectF(0f, 0f, row.width.toFloat(), row.height.toFloat())), area,
            under = HoldPopup.Button("Manage groups") { popupManage(scope) })
    }

    /** Manage groups: the window gives way to Manage groups for the group's playlist, that group's row selected. */
    private fun popupManage(scope: GuideScope.Group) {
        popup.close(fade = false)
        host.openManageGroups(scope.playlistId, scope.groupId)
    }

    /**
     * Move to top: the group becomes the first of its playlist's groups (the order Manage groups › Reorder groups
     * keeps), shown in the column at once with focus following it; stored behind.
     */
    private fun moveGroupToTop(scope: GuideScope.Group) {
        val moved = sidebar.moveGroupToTop(scope.playlistId, scope.groupId)
        popup.close()
        if (!moved) return
        writes.execute {
            // The shown groups in the order stored now, this one first (hidden ones keep theirs).
            val stored = runCatching {
                db.setGroupOrder(scope.playlistId, GroupEdits.toTop(db.groups(scope.playlistId).map { it.id }, scope.groupId))
            }.onFailure { Log.w(TAG, "Group order not stored: ${it.javaClass.simpleName}") }.isSuccess
            main.post { if (!released) announce(scope.playlistId, stored) }
        }
    }

    /**
     * Hide category: the group leaves the column at once and focus goes to the row in its place; the guide showing it
     * shows that row's channels instead. Stored behind; Manage groups shows it again.
     */
    private fun hideGroup(scope: GuideScope.Group) {
        val landing = sidebar.hideGroup(scope.playlistId, scope.groupId)
        if (landing != null) {
            popupFocusBack = { sidebar.focusScope(landing) }
            // No longer one of the column's groups: TV Guide mustn't bring it back as the guide's list (focusPlaying).
            // This page skips its own reload for this change; if the change isn't stored, the reload puts it back.
            groupNames = groupNames - (scope.playlistId to scope.groupId)
        }
        popup.close()
        if (landing == null) return
        showNotice("Hidden. Show it again in Manage groups.")
        writes.execute {
            val stored = runCatching { db.setGroupHidden(scope.playlistId, scope.groupId, true) }
                .onFailure { Log.w(TAG, "Group not hidden: ${it.javaClass.simpleName}") }.isSuccess
            main.post {
                if (released) return@post
                announce(scope.playlistId, stored)
                // Lists that held its channels lose them (one read before this was stored may still have them).
                val s = this.scope
                if (stored && (s == GuideScope.AllPlaylists || s == GuideScope.AllChannels(scope.playlistId))) {
                    loadChannels(focus = guide.focusedChannel()?.key, enterGrid = false)
                }
            }
        }
    }

    /**
     * Tells other open views (the settings windows) about a change made here. This page has it on screen already, so
     * its own listener lets it pass, unless it wasn't stored: then the page reads what is.
     */
    private fun announce(playlistId: String, stored: Boolean) {
        if (stored && started) ownChanges++
        sync.changed(playlistId)
    }

    /** [r], in [v]'s coordinates, in the page's own (where the small window is placed). */
    private fun inRoot(v: View, r: RectF): RectF {
        val a = IntArray(2)
        v.getLocationInWindow(a)
        val b = IntArray(2)
        root.getLocationInWindow(b)
        return RectF(r).apply { offset((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat()) }
    }

    /**
     * A window opened from the extra row closed: focus goes back to its button (the controls stayed up while it was
     * open), and the controls' timer starts again, as in the VOD player.
     */
    private fun backFromWindow(from: View) {
        if (released || !fullScreen) return
        if (controls.controlsShown) {
            from.requestFocus()
            more.refreshValues()
            bumpControls()
        } else {
            showControls(from)
        }
    }

    /** The programme on the playing channel now, from the guide (as the controls show it), or null. */
    private fun programmeNow(): Programme? {
        val key = ChannelKey.parse(player.currentKey) ?: return null
        val ch = guide.channelList().firstOrNull { it.key == key } ?: playingChannel?.takeIf { it.key == key } ?: return null
        val now = System.currentTimeMillis()
        val progs = guide.programmesOf(ch).ifEmpty { controlsProgrammes.takeIf { controlsProgrammesFor == ch.key }.orEmpty() }
        return progs.firstOrNull { it.isOn(now) }
    }

    /**
     * The sleep timer ran out: live playback stops (so nothing keeps streaming or holds the provider's connection),
     * full screen goes back to the guide, and a note says why.
     */
    private fun stopForSleep() {
        // Nothing playing any more (a film started meanwhile, say): nothing to stop or say.
        if (released || player.currentKey == null) return
        if (host.sidePanel.isOpen) host.sidePanel.close()
        player.stop()
        if (fullScreen) toGuide() else placeMini()
        showNotice("Sleep timer: Live TV stopped")
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, 3_500)
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // ---- Idle and clock -------------------------------------------------------------------------------------------

    private val idleToFull = Runnable {
        if (!started || fullScreen || !root.isShown) return@Runnable
        // Not while the viewer is in a dialog, the settings window, a small window or on the rail: try again later.
        if (FireDialog.isShowing(activity) || host.overlayOpen() || popup.isOpen || host.railHasFocus()) { scheduleIdle(); return@Runnable }
        // Not onto a channel that couldn't be played.
        if (player.status().error != null) return@Runnable
        // Not onto a paused channel's still picture: tried again later (it may carry on by itself).
        if (player.paused) { scheduleIdle(); return@Runnable }
        toFull()
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleToFull)
        val s = prefs.fullScreenAfterSec
        if (s > 0 && !fullScreen) main.postDelayed(idleToFull, s * 1000L)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (fullScreen && controls.visibility == View.VISIBLE && controls.tick(System.currentTimeMillis())) refreshControls()
            detailChannelObj?.let { showDetails(it, guide.focusedCell()) }
            main.postDelayed(this, 15_000)
        }
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    /** The activity hands every key here first while this tab shows. True when used. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (FireDialog.isShowing(activity)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) {
            scheduleIdle()
            keyAt = event.eventTime
        }
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BACK) return back(event)

        // Play/Pause in the guide: the miniature's channel pauses or carries on, as in full screen, with a word to say so.
        // Never left unused: Android would hand it to the last app that played something (a paused Stremio film,
        // seen on the Fire TV), which would start playing behind this page.
        if (!fullScreen && code in PLAY_PAUSE_KEYS) {
            if (down && event.repeatCount == 0 && player.playingKey != null && player.status().error == null && pauseWanted(code)) {
                pauseQuietly()
            }
            return true
        }

        // Menu over a small window: it closes first (the selection back where it was), then Settings opens as usual.
        if (code == KeyEvent.KEYCODE_MENU && down && popup.isOpen) popup.close(fade = false)

        if (!fullScreen) {
            // Channel +/- in the guide page through channels.
            return false
        }

        if (code in PLAY_PAUSE_KEYS) {
            if (down && event.repeatCount == 0) {
                if (pauseWanted(code)) togglePause()
                else if (!controls.controlsShown) showControls()
            }
            return true
        }

        // The remote's Guide key: back to the guide on the channel playing, as the TV Guide button (controls up or not).
        if (code == KeyEvent.KEYCODE_GUIDE) {
            if (down && event.repeatCount == 0) toGuide(toPlaying = true)
            return true
        }

        if (controls.controlsShown) {
            // The key that showed the controls doesn't also act on them while it's held (design-layout).
            if (code == revealKey) {
                if (!down) revealKey = 0
                return true
            }
            if (down) bumpControls()
            when (code) {
                // Down from the buttons: the extra row under the chevron, as in the VOD player; Up brings them back.
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && !controls.moreShown) controls.showMoreRow()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (controls.moreShown) {
                    if (down) controls.showMainRow()
                    return true
                }
                // Along the extra row, left from its first button goes round to the last and right from the last to
                // the first.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (down && controls.wrapMore(code == KeyEvent.KEYCODE_DPAD_LEFT, event.repeatCount == 0)) return true
            }
            return false
        }

        // Full screen, controls hidden.
        if (!down) return code in HANDLED_UP
        val channelButtonsOnly = prefs.channelButtonsOnly
        when (code) {
            // Up (and Channel +) goes on down the guide's list to the next channel, Down (Channel -) back to the one
            // before (the user's choice), round at the ends.
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1, event.repeatCount > 0)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (channelButtonsOnly) reveal(code, event) else zap(+1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (channelButtonsOnly) reveal(code, event) else zap(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> reveal(code, event)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> controls.showInfo().also { main.removeCallbacks(hideControls); main.postDelayed(hideControls, INFO_MS) }
            else -> return false
        }
        return true
    }

    /**
     * Play/Pause while a settings window, a small window or the rail has the keys ([dispatchKey] isn't asked then):
     * the channel still pauses or carries on, as the VOD player does, with focus left where it is. True for those
     * keys, used or not (left to Android, they'd go to the last app that played something).
     */
    fun mediaKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in PLAY_PAUSE_KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (player.playingKey == null || player.status().error != null || !pauseWanted(code)) return true
        if (fullScreen && !FireDialog.isShowing(activity) && !host.overlayOpen()) togglePause() else pauseQuietly()
        return true
    }

    /** Whether [code] pauses or carries on now (Pause while paused, or Play while playing, does nothing). */
    private fun pauseWanted(code: Int): Boolean {
        val wantsPause = code == KeyEvent.KEYCODE_MEDIA_PAUSE
        val wantsPlay = code == KeyEvent.KEYCODE_MEDIA_PLAY
        return (wantsPause && !player.paused) || (wantsPlay && player.paused) || (!wantsPause && !wantsPlay)
    }

    /** Pauses or carries on with a word to say so, the controls and focus left as they are. */
    private fun pauseQuietly() {
        player.togglePause()
        controls.setStatus(player.status())
        showNotice(if (player.paused) "Paused" else "Playing")
    }

    private fun reveal(code: Int, event: KeyEvent) {
        if (event.repeatCount != 0) return
        revealKey = code
        showControls()
    }

    /** A Back press this page took the DOWN of: only its UP acts (a stray UP after a dialog closed doesn't). */
    private var backDownSeen = false
    /** A Back press that closed a small window: held or released, it does nothing more (no full screen). */
    private var backClosedPopup = false

    private fun back(event: KeyEvent): Boolean {
        if (popup.isOpen || backClosedPopup) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                popup.close()
                backClosedPopup = true
            } else {
                backClosedPopup = false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Held Back in the guide: the playing channel goes full screen (design-layout).
            if (!fullScreen && event.repeatCount >= 1 && !backLongPressed && player.playingKey != null && player.status().error == null) {
                backLongPressed = true
                backDownSeen = false
                toFull()
                return true
            }
            if (event.repeatCount == 0) backDownSeen = fullScreen || guide.hasFocus()
            return backDownSeen || backLongPressed
        }
        // ACTION_UP
        if (backLongPressed) { backLongPressed = false; return true }
        if (!backDownSeen) return false
        backDownSeen = false
        if (fullScreen) {
            if (controls.controlsShown) hideControlsNow() else toGuide()
            return true
        }
        if (guide.hasFocus()) { openSidebar(focus = true); return true }
        return false
    }

    val isFullScreen get() = fullScreen

    /** The Info pods setting changed: the full-screen controls show the pods it now lists. */
    fun refreshPods() = controls.setStatus(player.status())

    companion object {
        private const val TAG = "FirePlayerLive"
        private const val INFO_MS = 4_000L
        /** A held zap key's channel is opened once no repeat has come for this long (repeats come about every 50 ms). */
        private const val ZAP_SETTLE_MS = 250L
        /** Column and rail sliding in and out. */
        private const val SLIDE_MS = 220L
        /** The miniature growing to full screen, and shrinking back. */
        private const val GROW_MS = 280L
        private const val SHRINK_MS = 240L
        /** A channel's small window stays this long after its star was pressed, so the star is seen to change. */
        private const val STAR_CLOSE_MS = 450L
        private val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        private val PLAY_PAUSE_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        private val HANDLED_UP = setOf(
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE,
        )
    }
}

/** A small rounded progress line (the details panel's programme progress). */
class ProgressLine(context: Context, private val t: Tokens) : View(context) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        // A blue bar on a light track (the concepts).
        val h = t.dpf(5f)
        val cy = height / 2f
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        p.color = 0x66FFFFFF
        canvas.drawRoundRect(r, h / 2, h / 2, p)
        r.right = (width * fraction).coerceAtLeast(h)
        p.color = t.accent
        canvas.drawRoundRect(r, h / 2, h / 2, p)
    }
}

/** The Live TV tab's own remembered state and its provisional settings (separate file: nothing existing changes). */
class LivePrefs(context: Context) {
    private val sp = context.getSharedPreferences("fireplayer_live", Context.MODE_PRIVATE)

    var scope: GuideScope?
        get() = GuideScope.decode(sp.getString("scope", null))
        set(v) = sp.edit().putString("scope", v?.encode()).apply()

    var focus: ChannelKey?
        get() = ChannelKey.parse(sp.getString("focus", null))
        set(v) = sp.edit().putString("focus", v?.toString()).apply()

    var lastChannel: ChannelKey?
        get() = ChannelKey.parse(sp.getString("last_channel", null))
        set(v) = sp.edit().putString("last_channel", v?.toString()).apply()

    /** Remote › "Channel ▲▼": false = D-pad + channel buttons (default), true = Channel buttons only. */
    var channelButtonsOnly: Boolean
        get() = sp.getBoolean("channel_buttons_only", false)
        set(v) = sp.edit().putBoolean("channel_buttons_only", v).apply()

    /** Seconds the full-screen controls stay without a key press; 0 = until Back ("Panels timeout"). Default 6 s. */
    var controlsHideSec: Int
        get() = sp.getInt("controls_hide_s", 6)
        set(v) = sp.edit().putInt("controls_hide_s", v).apply()

    /** Seconds without a key press before the guide's miniature goes full screen; 0 = never. Default 1 minute (user). */
    var fullScreenAfterSec: Int
        get() = sp.getInt("full_after_s", 60)
        set(v) = sp.edit().putInt("full_after_s", v).apply()

    /** Live FirePlayer Settings › Update interval: how often the EPGs are read again (hours; default 24). */
    var epgUpdateHours: Int
        get() = sp.getInt("epg_update_h", 24)
        set(v) = sp.edit().putInt("epg_update_h", v).apply()

    /** Live TV buffer: how much of a channel is loaded ahead. Default Medium, the buffer live TV always had. */
    var liveBuffer: app.fireplayer.player.LiveBuffer
        get() = app.fireplayer.player.LiveBuffer.named(sp.getString("live_buffer", null))
        set(v) = sp.edit().putString("live_buffer", v.name).apply()
}
