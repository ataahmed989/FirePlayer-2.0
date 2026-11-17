package app.fireplayer.live.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
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
import java.util.Date
import java.util.concurrent.Executors

/**
 * The Live TV tab: one experience of guide and player (design-layout, "Live TV guide and playback").
 *
 * Guide: playlist/group column, the miniature top left with the selected programme's details beside it, and the
 * compact grid below. The column collapses while the grid has focus. First Center on another channel tunes it in the
 * miniature; Center on the playing channel opens full screen; holding Back opens full screen; so does a period
 * without key presses ([LivePrefs.fullScreenAfterSec]). The miniature and full screen are the same player and
 * surface, so going between them never retunes.
 *
 * Full screen: with the controls hidden, Up/Down and Channel +/- change channel (or only Channel +/- when
 * "Channel buttons only" is set, and then Up/Down show the controls); Center shows the controls; Back returns to
 * the guide with its group, channel and time as they were. With the controls shown, the D-pad moves round them and
 * Back hides them first.
 */
class LivePage(private val activity: Activity, private val host: Host) {

    interface Host {
        val tokens: Tokens
        /** Live video at [rect] (root coordinates) with rounded corners, or the whole screen, or nowhere (null). */
        fun placeVideo(rect: RectF?, radius: Float, full: Boolean)
        fun setChromeVisible(visible: Boolean)
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
        /** The Live FirePlayer Settings window over this page (Menu, or the Settings button in full screen). */
        fun openPlayerSettings()
    }

    private val t = host.tokens
    private val ctx: Context = activity
    private val db = LiveDb.get(ctx)
    private val sync = LiveSync.get(ctx)
    private val player = PlayerHost.get(ctx)
    private val prefs = LivePrefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    val root = FrameLayout(ctx)
    private val guideLayer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val sidebarPanel = FrameLayout(ctx)
    val sidebar = GuideSidebar(ctx, t)
    /** Where the miniature goes. With nothing playing it's a panel with a hint; while playing it's see-through. */
    private val videoSlot = t.label(ctx, 14f, color = t.textMuted).apply {
        text = "Press OK on a channel to watch it here"
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(t.dp(24), 0, t.dp(24), 0)
    }
    private var placedAt: RectF? = null
    private val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val detailChannel = t.label(ctx, 13f, t.semibold, t.textMuted)
    private val detailTitle = t.label(ctx, 19f, t.bold)
    private val detailTime = t.label(ctx, 14f)
    private val detailBar = ProgressLine(ctx, t)
    private val detailLeft = t.label(ctx, 14f, color = t.textMuted)
    private val detailDesc = t.label(ctx, 14f, color = t.textMuted).apply { maxLines = 3 }
    private val detailNext = t.label(ctx, 14f, color = t.textMuted)
    private val gridPanel = FrameLayout(ctx)
    val guide = GuideView(ctx, t)
    private val controls = LiveControls(ctx, t)
    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val emptyText = t.label(ctx, 18f, t.semibold).apply { maxLines = 3; gravity = Gravity.CENTER }
    private val emptyButton = t.label(ctx, 16f, t.semibold)
    private val notice = t.label(ctx, 15f).apply { maxLines = 2 }

    private val logos = LogoCache(t.dp(76)) { guide.invalidate(); refreshControls() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerGuide").apply { priority = Thread.NORM_PRIORITY - 1 } }
    /** The viewer's own changes (favorites) are stored here, not queued behind the guide's reads. */
    private val writes = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerGuideWrites") }

    private var playlists: List<Playlist> = emptyList()
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

    init {
        build()
        wire()
    }

    // ---- Layout ---------------------------------------------------------------------------------------------------

    private fun build() {
        val m = t.dp(12)
        // Column: playlists and groups.
        sidebarPanel.addView(sidebar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        guideLayer.addView(sidebarPanel, LinearLayout.LayoutParams(t.dp(212), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = t.dp(10) })

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val previewH = t.dp(140)
        topRow.addView(videoSlot, LinearLayout.LayoutParams(previewH * 16 / 9, previewH))
        details.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10))
        details.addView(detailChannel)
        details.addView(detailTitle, lp(top = 6))
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailTime)
            addView(detailBar, LinearLayout.LayoutParams(t.dp(56), t.dp(8)).apply { leftMargin = t.dp(12); rightMargin = t.dp(10) })
            addView(detailLeft)
        }
        details.addView(timeRow, lp(top = 6))
        details.addView(detailDesc, lp(top = 8))
        details.addView(detailNext, lp(top = 6))
        topRow.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = t.dp(12) })
        right.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewH))
        gridPanel.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6))
        gridPanel.addView(guide, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        right.addView(gridPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = t.dp(10) })
        guideLayer.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(guideLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(m, m, m, m) })

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
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty.visibility = View.GONE

        notice.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10))
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = t.dp(30) })
        notice.visibility = View.GONE
        applyLook()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(top) }

    /** Panels get the look's surface (glass over the blurred wallpaper, or the original dark panel). */
    fun applyLook() {
        Glass.background(sidebarPanel, Glass.Kind.PANEL)
        Glass.background(details, Glass.Kind.PANEL)
        Glass.background(gridPanel, Glass.Kind.PANEL)
        Glass.background(notice, Glass.Kind.POPUP)
        Glass.background(emptyButton, Glass.Kind.PILL)
        guide.glass = Glass.on
        controls.applyLook()
        if (placeholder == true) { placeholder = null; showSlotPlaceholder(true) }
    }

    private fun wire() {
        // The slot moves when the column opens or closes (its parent moves, not the slot within it): every layout pass
        // compares where it is on screen with where the video was put.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (fullScreen || !root.isShown) return@addOnGlobalLayoutListener
            if (player.currentKey == null) {
                if (placeholder != true) placeMini()
                return@addOnGlobalLayoutListener
            }
            val r = rectOf(videoSlot) ?: return@addOnGlobalLayoutListener
            if (r != placedAt) placeMini()
        }
        guide.callbacks = object : GuideView.Callbacks {
            override fun onFocusMoved(channel: Channel, cell: Cell?) = showDetails(channel, cell)
            override fun onPick(channel: Channel, cell: Cell?) = pick(channel)
            override fun onLeaveLeft() = openSidebar(focus = true)
            override fun needProgrammes(channels: List<Channel>, from: Long, to: Long) = loadProgrammes(channels, from, to)
            override fun logo(url: String): Bitmap? = logos.get(url)
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
        }
        controls.actions = object : LiveControls.Actions {
            override fun onGuide() = toGuide()
            override fun onHistory() = showHistory()
            override fun onCaptions() = chooseCaptions()
            override fun onFavorite() = toggleFavorite()
            override fun onPlayPause() = togglePause()
            override fun onGoLive() = goLive()
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    private val statusListener = object : PlayerHost.Listener {
        override fun onLiveStatus(status: PlayerHost.Status) {
            guide.playingKey = ChannelKey.parse(status.key)
            controls.setStatus(status)
            status.error?.let { showNotice(it) }
        }
    }

    private val dataListener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) = reload(keepFocus = true)
    }

    /** The tab is showing (activity started, or switched to this tab). */
    fun start() {
        if (started) return
        started = true
        player.addListener(statusListener)
        sync.addListener(dataListener)
        // A channel that was playing comes back in the miniature (not full screen).
        if (!player.onScreenStarted() && player.currentKey == null) {
            prefs.lastChannel?.let { key -> resumeLast(key) }
        }
        reload(keepFocus = true)
        scheduleIdle()
        main.post(clockTick)
    }

    /** The tab is hidden or the activity stopped: the stream is released, the channel remembered. */
    fun stop() {
        if (!started) return
        started = false
        player.removeListener(statusListener)
        sync.removeListener(dataListener)
        player.onScreenStopped(host.videoOwner)
        placedAt = null
        main.removeCallbacks(idleToFull)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(hideControls)
        main.removeCallbacks(hideNotice)
        // The controls' timer is gone: don't leave them up for the return.
        if (fullScreen) controls.hideAll()
        filmGlass(false)
        revealKey = 0
        backDownSeen = false
    }

    fun release() {
        stop()
        released = true
        ++reloadSerial
        ++loadSerial
        io.shutdown()
        // Queued favorites are still stored.
        writes.shutdown()
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
        }
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
        val playlistName = playlists.firstOrNull { it.id == channel.playlistId }?.name
        detailChannel.text = "${channel.number}  ${channel.name}" + (if (playlists.size > 1 && playlistName != null) " · $playlistName" else "")
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
        val next = nextAfter(channel, p.stop)
        detailNext.text = next?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
    }

    private fun showDetailsEmpty(text: String) {
        // Nothing focused: the clock's refresh mustn't bring back the previous scope's channel.
        detailChannelObj = null
        detailCell = null
        detailChannel.text = ""
        detailTitle.text = text
        detailTime.text = ""
        detailBar.visibility = View.GONE
        detailLeft.text = ""
        detailDesc.text = ""
        detailNext.text = ""
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

    private fun tune(channel: Channel, known: Playlist? = null): Boolean {
        val playlist = known ?: playlists.firstOrNull { it.id == channel.playlistId } ?: return false
        val result = player.tune(LiveSession.Source(channel.key.toString(), channel.url, playlist.id, playlist.maxConnections))
        if (result == LiveSession.Tune.NoConnection) {
            showNotice("This playlist's connection limit is reached")
            return false
        }
        prefs.lastChannel = channel.key
        playingChannel = channel
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

    private fun resumeLast(key: ChannelKey) {
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            main.post { if (started && player.currentKey == null) tune(ch, pl) }
        }
    }

    private fun openSidebar(focus: Boolean) {
        sidebarOpen = true
        sidebarPanel.visibility = View.VISIBLE
        if (focus) sidebar.focusCurrent()
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        sidebarPanel.visibility = View.GONE
    }

    private fun placeMini() {
        val playing = player.currentKey != null
        if (playlists.isEmpty() || !playing) {
            placedAt = null
            host.placeVideo(null, 0f, false)
            showSlotPlaceholder(true)
            return
        }
        val r = rectOf(videoSlot) ?: return
        placedAt = r
        showSlotPlaceholder(false)
        host.placeVideo(r, t.dpf(12f), false)
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
        fullScreen = true
        revealKey = 0
        focusBeforeFull = guide.focusedChannel()?.key
        zappedInFull = false
        main.removeCallbacks(idleToFull)
        // Nothing in the hidden guide may keep or take focus: in full screen the keys come to dispatchKey.
        guideLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        activity.currentFocus?.clearFocus()
        guideLayer.visibility = View.INVISIBLE
        host.setChromeVisible(false)
        placedAt = null
        host.placeVideo(null, 0f, true)
        refreshControls()
        controls.showInfo()
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, INFO_MS)
    }

    fun toGuide() {
        if (!fullScreen) return
        fullScreen = false
        revealKey = 0
        controls.hideAll()
        filmGlass(false)
        host.setChromeVisible(true)
        guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        guideLayer.visibility = View.VISIBLE
        placeMini()
        // Back to where the guide was, unless the channel changed in full screen: then to that channel.
        val playing = ChannelKey.parse(player.currentKey)
        if (zappedInFull && playing != null) guide.focusChannel(playing)
        else focusBeforeFull?.let { k -> if (guide.focusedChannel()?.key != k) guide.focusChannel(k) }
        guide.requestFocus()
        scheduleIdle()
    }

    private fun refreshControls() {
        val key = ChannelKey.parse(player.currentKey) ?: return
        // The guide's copy first (fresh favorite state), else the channel as tuned: it may be outside this scope.
        val ch = guide.channelList().firstOrNull { it.key == key } ?: playingChannel?.takeIf { it.key == key }
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

    private fun zap(delta: Int) {
        val list = guide.channelList()
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.key.toString() == player.currentKey }
        val next = list[Math.floorMod((if (cur < 0) 0 else cur) + delta, list.size)]
        if (tune(next)) {
            zappedInFull = true
            guide.focusChannel(next.key)
            refreshControls()
            if (!controls.controlsShown) {
                controls.showInfo()
                main.removeCallbacks(hideControls)
                main.postDelayed(hideControls, INFO_MS)
            }
        }
    }

    private val hideControls: Runnable = Runnable {
        if (!fullScreen) return@Runnable
        // A dialog or the settings window is open over the controls: wait for it rather than pulling focus out.
        if (FireDialog.isShowing(activity) || host.overlayOpen()) {
            main.postDelayed(hideControls, 2_000)
            return@Runnable
        }
        hideControlsNow()
    }

    private fun hideControlsNow() {
        main.removeCallbacks(hideControls)
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

    private fun showControls() {
        refreshControls()
        filmGlass(true)
        controls.showControls()
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
                    recent.getOrNull(i)?.let { ch -> if (tune(ch)) { zappedInFull = true; refreshControls() } }
                }
            }
        }
    }

    private fun chooseCaptions() {
        val tracks = player.status().captions
        if (tracks.isEmpty()) { showNotice("This channel has no captions"); return }
        val options = listOf("Off") + tracks.map { it.label }
        val selected = tracks.indexOfFirst { it.selected }.let { if (it < 0) 0 else it + 1 }
        FireDialog.choose(activity, "Captions", options, selected) { i ->
            player.selectCaption(if (i == 0) null else tracks.getOrNull(i - 1))
        }
    }

    private fun toggleFavorite() {
        val key = ChannelKey.parse(player.currentKey) ?: return
        val ch = guide.channelList().firstOrNull { it.key == key } ?: playingChannel?.takeIf { it.key == key } ?: return
        val fav = !ch.favorite
        // Shown at once (the star fills, the guide row follows); stored behind it. The database can be busy for a
        // while with a guide update, which must not hold up the button.
        guide.updateChannels(guide.channelList().map { if (it.key == key) it.copy(favorite = fav) else it })
        playingChannel = ch.copy(favorite = fav)
        refreshControls()
        showNotice(if (fav) "Added to Favorites" else "Removed from Favorites")
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
                    guide.updateChannels(guide.channelList().map { if (it.key == key) it.copy(favorite = fav) else it })
                    playingChannel = playingChannel?.let { if (it.key == key) it.copy(favorite = fav) else it }
                    refreshControls()
                }
            }
        }
    }

    private fun showMore() {
        val audio = player.status().audioTracks
        val answers = ArrayList<Pair<String, () -> Unit>>()
        if (audio.size > 1) answers += "Audio track" to {
            FireDialog.choose(activity, "Audio track", audio.map { it.label }, audio.indexOfFirst { it.selected }) { i ->
                audio.getOrNull(i)?.let { player.selectAudio(it) }
            }
        }
        answers += "Back to the guide" to { toGuide() }
        FireDialog.ask(activity, "More", answers)
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
        // Not while the viewer is in a dialog, the settings window or on the rail: try again later.
        if (FireDialog.isShowing(activity) || host.overlayOpen() || host.railHasFocus()) { scheduleIdle(); return@Runnable }
        // Not onto a channel that couldn't be played.
        if (player.status().error != null) return@Runnable
        toFull()
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleToFull)
        val s = prefs.fullScreenAfterSec
        if (s > 0 && !fullScreen) main.postDelayed(idleToFull, s * 1000L)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (fullScreen && controls.visibility == View.VISIBLE) controls.tick(System.currentTimeMillis())
            detailChannelObj?.let { showDetails(it, guide.focusedCell()) }
            main.postDelayed(this, 15_000)
        }
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    /** The activity hands every key here first while this tab shows. True when used. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (FireDialog.isShowing(activity)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) scheduleIdle()
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BACK) return back(event)

        if (!fullScreen) {
            // Channel +/- in the guide page through channels.
            return false
        }

        if (code in PLAY_PAUSE_KEYS) {
            if (down && event.repeatCount == 0) {
                val wantsPause = code == KeyEvent.KEYCODE_MEDIA_PAUSE
                val wantsPlay = code == KeyEvent.KEYCODE_MEDIA_PLAY
                if ((wantsPause && !player.paused) || (wantsPlay && player.paused) || (!wantsPause && !wantsPlay)) togglePause()
                else if (!controls.controlsShown) showControls()
            }
            return true
        }

        if (controls.controlsShown) {
            // The key that showed the controls doesn't also act on them while it's held (design-layout).
            if (code == revealKey) {
                if (!down) revealKey = 0
                return true
            }
            // Down from the buttons: more options, as in the VOD player.
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down && event.repeatCount == 0) showMore()
                return true
            }
            if (down) bumpControls()
            return false
        }

        // Full screen, controls hidden.
        if (!down) return code in HANDLED_UP
        val channelButtonsOnly = prefs.channelButtonsOnly
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(+1)
            KeyEvent.KEYCODE_DPAD_UP -> if (channelButtonsOnly) reveal(code, event) else zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (channelButtonsOnly) reveal(code, event) else zap(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> reveal(code, event)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> controls.showInfo().also { main.removeCallbacks(hideControls); main.postDelayed(hideControls, INFO_MS) }
            KeyEvent.KEYCODE_GUIDE -> toGuide()
            else -> return false
        }
        return true
    }

    private fun reveal(code: Int, event: KeyEvent) {
        if (event.repeatCount != 0) return
        revealKey = code
        showControls()
    }

    /** A Back press this page took the DOWN of: only its UP acts (a stray UP after a dialog closed doesn't). */
    private var backDownSeen = false

    private fun back(event: KeyEvent): Boolean {
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
        val h = t.dpf(3f)
        val cy = height / 2f
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        p.color = 0x40FFFFFF
        canvas.drawRoundRect(r, h, h, p)
        r.right = width * fraction
        p.color = t.text
        canvas.drawRoundRect(r, h, h, p)
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

    /** "Channel changing buttons": false = D-pad + channel buttons (default), true = Channel buttons only. */
    var channelButtonsOnly: Boolean
        get() = sp.getBoolean("channel_buttons_only", false)
        set(v) = sp.edit().putBoolean("channel_buttons_only", v).apply()

    /** Seconds the full-screen controls stay without a key press; 0 = until Back ("Panels timeout"). Default 6 s. */
    var controlsHideSec: Int
        get() = sp.getInt("controls_hide_s", 6)
        set(v) = sp.edit().putInt("controls_hide_s", v).apply()

    /** Seconds without a key press before the guide's miniature goes full screen; 0 = never. Provisional: 30 s. */
    var fullScreenAfterSec: Int
        get() = sp.getInt("full_after_s", 30)
        set(v) = sp.edit().putInt("full_after_s", v).apply()

    /** Live FirePlayer Settings › Update interval: how often the EPGs are read again (hours; default 24). */
    var epgUpdateHours: Int
        get() = sp.getInt("epg_update_h", 24)
        set(v) = sp.edit().putInt("epg_update_h", v).apply()
}
