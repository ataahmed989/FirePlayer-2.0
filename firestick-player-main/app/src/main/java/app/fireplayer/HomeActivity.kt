package app.fireplayer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import app.fireplayer.data.Dimmer
import app.fireplayer.data.Prefs
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveSync
import app.fireplayer.live.ui.LivePage
import app.fireplayer.live.ui.LivePicture
import app.fireplayer.live.ui.LiveSettings
import app.fireplayer.playback.PlayerHost
import app.fireplayer.player.PictureFit
import app.fireplayer.shell.NavRail
import app.fireplayer.shell.Tokens
import app.fireplayer.shell.WallpaperView
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyler
import app.fireplayer.titles.TitleService
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassBackdrop
import app.fireplayer.ui.GlassSource
import app.fireplayer.ui.Rows
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.update.Updater

/**
 * What opens from the Fire TV home screen: the app shell. A tab rail (one FirePlayer mark; only tabs that work are
 * shown) and its pages: Live TV (guide, miniature and full-screen player) and Settings: every setting in its category
 * (Playlists & EPG, Live TV, Playback, Remote & skipping, Subtitles & audio, …), each category its own window, and
 * Open a link, as the old home menu had.
 *
 * Both looks share this layout, each over its own background (the user's): glass shows the blurred beach wallpaper,
 * blurred again behind glass shapes ([GlassSource.Host]); classic the original dark surfaces over the dark fog.
 */
class HomeActivity : AppCompatActivity(), GlassSource.Host, LivePage.Host {

    private var still: GlassSource? = null
    /** The live picture, blurred, for the full-screen controls' glass (Android 7+); made when first needed. */
    private var film: GlassBackdrop? = null
    private var filmOn = false
    override val glassSource: GlassSource? get() = if (filmOn) film ?: still else still
    private lateinit var shellTokens: Tokens
    override val tokens: Tokens get() = shellTokens

    private lateinit var settingsPanels: SettingsPanels
    private lateinit var panel: SidePanel
    private lateinit var liveSettings: LiveSettings
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var captions: SubtitleView
    private val captionStyler by lazy { SubtitleStyler(this) }
    private lateinit var wallpaper: WallpaperView
    private lateinit var pages: FrameLayout
    private lateinit var rail: NavRail
    private lateinit var live: LivePage
    private lateinit var settingsPage: LinearLayout
    private var consumeBackUp = false
    private var lookOn = false
    private var videoTarget: RectF? = null
    private var videoFull = false
    private var videoRadius = 0f
    /** Darkens the full-screen live picture (Screen dimmer), under the controls. */
    private lateinit var liveDimmer: View

    private val player by lazy { PlayerHost.get(this) }
    /** Display size (shared with the VOD player) and the screen dimmer's customs, read as the live picture is laid out. */
    private val videoPrefs by lazy { Prefs(this) }
    private val dimmer by lazy { Dimmer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        Glass.on = prefs.glassLook
        shellTokens = Tokens(this)
        setContentView(R.layout.activity_home)
        root = findViewById(R.id.home_root)
        surface = findViewById(R.id.live_surface)
        captions = findViewById(R.id.live_captions)
        liveDimmer = findViewById(R.id.live_dimmer)
        pages = findViewById(R.id.page_container)
        settingsPanels = SettingsPanels(this, prefs, SubtitlePresets(prefs))
        panel = SidePanel(this)
        liveSettings = LiveSettings(this, panel)
        // App settings' list opens Playlists & EPG and Live TV from here.
        settingsPanels.liveWindows = { liveSettings.openCategory(it) }

        wallpaper = WallpaperView(this).apply { glassLook = Glass.on }
        findViewById<FrameLayout>(R.id.wallpaper_host).addView(wallpaper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wallpaper.onReady = { glass ->
            still = glass
            // Glass made before the wallpaper was ready showed the dark fill: made again now with the wallpaper.
            if (Glass.on) applyLook()
        }

        rail = NavRail(this, tokens)
        findViewById<FrameLayout>(R.id.rail_host).addView(rail, FrameLayout.LayoutParams(rail.collapsedWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        rail.onTab = { showTab(it) }
        rail.onEnterPage = { focusPage() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)

        live = LivePage(this, this)
        pages.addView(live.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        settingsPage = buildSettingsPage()
        pages.addView(settingsPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { leftMargin = tokens.dp(40) })

        player.attach(surface)
        player.addListener(videoSizeListener)

        lookOn = Glass.on
        clipForLook()
        settingsPanels.onLookChanged = { applyLook() }
        // Info pods are one setting for films and live channels: the live controls follow a change at once.
        settingsPanels.onChipsChanged = { live.refreshPods() }
        showTab(NavRail.Tab.LIVE)
        root.post { live.takeFocus() }

        // A test build is signed differently from the releases, so a release can't install over it: don't offer one.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            val updater = Updater(this)
            updater.check { release -> release?.let { updater.offer(it) } }
        }
        // Normally running since the TV started; the first time after installing, from here.
        TitleService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // This screen's surface shows live again (another instance may have taken it meanwhile).
        player.attach(surface)
        if (rail.selected == NavRail.Tab.LIVE) live.start()
        // The dimmer may have been changed in the VOD player meanwhile.
        applyLiveDim()
        // Playlists and EPGs not updated within their intervals are updated in the background: at start, and each
        // time the app comes back (it can stay alive for days on a TV).
        LiveSync.get(this).updateDue()
    }

    override fun onStop() {
        live.stop()
        film?.stop()
        root.keepScreenOn = false
        super.onStop()
    }

    override val videoOwner: Any get() = surface

    override fun overlayOpen(): Boolean = panel.isOpen || FireDialog.isShowing(this)

    override val sidePanel: SidePanel get() = panel

    /** Glass made while the full-screen controls show the live picture is dimmed like the VOD player's glass. */
    override val glassShowsFilm: Boolean get() = filmOn

    override fun pictureChanged() {
        layoutVideo()
        applyLiveDim()
    }

    override fun setFilmGlass(on: Boolean) {
        filmOn = on && Glass.on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        if (filmOn) {
            val f = film ?: GlassBackdrop(this, surface).also { film = it }
            f.start()
            f.playing = player.status().playing
            f.frameChanged()
        } else {
            film?.stop()
        }
    }

    override fun onDestroy() {
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        player.removeListener(videoSizeListener)
        player.detach(surface)
        live.release()
        liveSettings.release()
        film?.release()
        super.onDestroy()
    }

    override fun onRestart() {
        super.onRestart()
        if (lookOn == Glass.on) return
        // The look was switched in the player while this screen was behind it.
        applyLook()
        if (panel.isOpen) {
            Glass.background(panel.root, Glass.Kind.PANEL)
            panel.refresh()
        }
    }

    // ---- Tabs -----------------------------------------------------------------------------------------------------

    private fun showTab(tab: NavRail.Tab) {
        rail.setSelected(tab)
        pageInset(tab)
        if (tab != NavRail.Tab.LIVE) setRailShown(true, 0L)
        val isLive = tab == NavRail.Tab.LIVE
        live.root.visibility = if (isLive) View.VISIBLE else View.GONE
        settingsPage.visibility = if (isLive) View.GONE else View.VISIBLE
        if (isLive) {
            live.start()
        } else {
            // Live playback doesn't follow to other tabs yet (the top-right live window is a later step).
            live.stop()
            placeVideo(null, 0f, false)
        }
    }

    private fun focusPage() {
        when (rail.selected) {
            NavRail.Tab.LIVE -> live.takeFocus()
            NavRail.Tab.SETTINGS -> settingsRows.firstOrNull()?.requestFocus()
        }
    }

    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        val inRail = now != null && isInside(now, rail)
        rail.setExpanded(inRail)
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    // ---- Settings tab ---------------------------------------------------------------------------------------------

    /** The Settings tab's rows, column by column: App settings' categories, then Open a link. */
    private val settingsRows = ArrayList<View>()

    /**
     * The Settings tab: a row for each category of App settings ([SettingsCategory]), each opening its own window,
     * and Open a link last. Rows that don't fit one column under the title go on in a second ([settingsColumns]).
     */
    private fun buildSettingsPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(TextView(this).apply {
            text = "Settings"
            typeface = tokens.bold
            setTextColor(tokens.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = tokens.dp(TITLE_GAP_DP) })
        val rows = SettingsCategory.entries.map { category ->
            SettingsRow(category.title, categoryIcon(category)) { from -> openCategory(category, from) }
        } + SettingsRow(SettingsCategory.OPEN_A_LINK, R.drawable.ic_link) { openLink() }
        val columns = SettingsCategory.inColumns(rows, settingsColumns(rows.size))
        val side = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Its own width (the columns'): left to match the page, it would only get the title's width, as the page is
        // as wide as its widest child.
        page.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Narrower in two columns, so the second stays clear of the settings window (its left edge is about 644 dp in).
        val width = tokens.dp(if (columns.size > 1) COLUMN_DP else ONE_COLUMN_DP)
        columns.forEachIndexed { i, column ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            side.addView(list, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = tokens.dp(COLUMN_GAP_DP)
            })
            for (row in column) settingsRows += addRow(list, row.label, row.icon, row.onClick)
        }
        return page
    }

    private class SettingsRow(val label: String, val icon: Int, val onClick: (View) -> Unit)

    /**
     * Columns for the Settings tab's [rows], from addRow's sizes: each row 44 dp and 8 dp under it, the title (26 sp,
     * about 34 dp) and its 16 dp gap, and 27 dp kept clear above and below (the 5% a TV may cut off). On the Fire TV's
     * 960 x 540 dp screen ten rows need 50 + 520 dp, more than its 540: two columns of five, so nothing has to scroll.
     */
    private fun settingsColumns(rows: Int): Int {
        val metrics = resources.displayMetrics
        return SettingsCategory.columnsFor(
            rows, screenDp = (metrics.heightPixels / metrics.density).toInt(), rowDp = ROW_DP, gapDp = ROW_GAP_DP,
            titleDp = TITLE_DP + TITLE_GAP_DP, edgeDp = SAFE_EDGE_DP,
        )
    }

    private fun categoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.PLAYLISTS_EPG -> R.drawable.ic_playlist_add
        SettingsCategory.LIVE_TV -> R.drawable.ic_live
        SettingsCategory.PLAYBACK -> R.drawable.ic_head_play
        SettingsCategory.REMOTE -> R.drawable.ic_head_remote
        SettingsCategory.SUBTITLES_AUDIO -> R.drawable.ic_subtitles
        SettingsCategory.LOOK -> R.drawable.ic_head_look
        SettingsCategory.ACCOUNTS -> R.drawable.ic_key
        SettingsCategory.TITLES -> R.drawable.ic_head_tv
        SettingsCategory.APP -> R.drawable.ic_head_app
    }

    /** A category's own window; the page dims while it's open and focus comes back to [from] when it closes. */
    private fun openCategory(category: SettingsCategory, from: View) {
        when (category) {
            SettingsCategory.PLAYLISTS_EPG -> liveSettings.openPlaylistsAndEpg(onClose = { restore(from) }).also { dim() }
            SettingsCategory.LIVE_TV -> liveSettings.openLiveOptions(onClose = { restore(from) }).also { dim() }
            else -> openPanel(category.title, from) { settingsPanels.renderCategory(this, category) }
        }
    }

    // Faded as one layer, drawn once rather than each frame, and dropped when the fade ends.
    private fun dim() = settingsPage.animate().alpha(0.35f).setDuration(160).withLayer().start()

    private fun restore(from: View) {
        settingsPage.animate().alpha(1f).setDuration(160).withLayer().start()
        // The row it was opened from, if it's on screen (Menu can open Settings over the Live TV tab).
        if (from.isShown) from.requestFocus() else focusPage()
    }

    private fun openPanel(title: String, from: View, render: SidePanel.() -> Unit) {
        // The menu steps back while a window is open, so the window stands out.
        dim()
        panel.open(title, onClose = { restore(from) }, render = render)
    }

    /** From the Live TV tab with no playlists: the Playlists window. */
    override fun openPlaylists() {
        liveSettings.openPlaylists(onClose = { live.takeFocus() })
    }

    /** Live FirePlayer Settings over the Live TV tab; focus returns where it was when it closes. */
    override fun openPlayerSettings(onClosed: (() -> Unit)?) {
        val back = currentFocus
        // The guide steps back while the window is open (the classic window is see-through), as the Settings tab does.
        live.root.animate().alpha(0.35f).setDuration(160).start()
        liveSettings.openPlayerSettings(settingsPanels, onClose = {
            live.root.animate().alpha(1f).setDuration(160).start()
            if (onClosed != null) onClosed() else if (back != null && back.isShown) back.requestFocus() else focusPage()
        })
    }

    /** Assign favorites over the Live TV tab (from the guide); focus returns where it was when it closes. */
    override fun openAssignFavorites(playlistId: String, groupId: String?, channel: ChannelKey?) {
        // A window already open closes first, so its own close runs (its focus, the guide brightening again).
        if (panel.isOpen) panel.close()
        val back = currentFocus
        // The guide steps back while the window is open, as for Live FirePlayer Settings: one fade, drawn as one layer.
        live.root.animate().alpha(0.35f).setDuration(160).withLayer().start()
        liveSettings.openAssignFavorites(playlistId, groupId, channel, onClose = {
            live.root.animate().alpha(1f).setDuration(160).withLayer().start()
            // Back where it was. A popup it came from is gone by now, and a row of the groups column may show another
            // group after the guide read its data again: then the page puts focus back on its current entry.
            if (back != null && back.isShown && !isInside(back, live.sidebar)) back.requestFocus() else focusPage()
        })
    }

    private fun openLink() {
        SidePanel.prompt(this, "Video link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
            var link = text.trim()
            if (link.isEmpty()) return@prompt
            // "example.com/movie.mkv" typed without http:// still opens.
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(link)) link = "http://$link"
            val scheme = Uri.parse(link).scheme?.lowercase()
            if (scheme !in PLAYABLE_SCHEMES) {
                FireDialog.ask(this, "FirePlayer can't open $scheme:// links.", listOf<Pair<String, () -> Unit>>("OK" to {}))
                return@prompt
            }
            // A film starts: live playback stops (design-layout).
            player.stop()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), this, PlayerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun addRow(list: LinearLayout, label: String, icon: Int, onClick: (View) -> Unit): View {
        val density = resources.displayMetrics.density
        val colors = ContextCompat.getColorStateList(this, R.color.sel_text_row)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            Glass.background(this, Glass.Kind.ROW)
            minimumHeight = (ROW_DP * density).toInt()
            val h = (14 * density).toInt()
            setPadding(h, 0, h, 0)
        }
        val iconView = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = colors
            isDuplicateParentStateEnabled = true
        }
        val size = (20 * density).toInt()
        row.addView(iconView, LinearLayout.LayoutParams(size, size).apply { marginEnd = (12 * density).toInt() })
        row.addView(TextView(this).apply {
            text = label
            typeface = ResourcesCompat.getFont(this@HomeActivity, R.font.figtree_600)
            setTextColor(colors)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isDuplicateParentStateEnabled = true
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { onClick(it) }
        // Up from the top row of a column goes to its bottom one and the other way round, like the settings lists;
        // left and right go across, to the row beside it (or the rail).
        row.setOnKeyListener { v, keyCode, event -> Rows.verticalNav(list, v, keyCode, event) }
        list.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (ROW_GAP_DP * density).toInt() },
        )
        return row
    }

    // ---- Look -----------------------------------------------------------------------------------------------------

    /** Gives the shell the look Settings now has (the settings window redraws its own). */
    private fun applyLook() {
        lookOn = Glass.on
        // Each look has its own background.
        wallpaper.glassLook = Glass.on
        // The rail's and Settings tab's glass blur the wallpaper (never the film, never dimmed), even when the look
        // changes over full screen; the live page re-makes its controls over the film itself.
        val film = filmOn
        filmOn = false
        for (row in settingsRows) Glass.background(row, Glass.Kind.ROW)
        rail.applyLook()
        filmOn = film && Glass.on
        live.applyLook()
        root.jumpDrawablesToCurrentState()
        clipForLook()
    }

    /** The glass settings window's shadow falls outside it, which this screen would otherwise cut off. */
    private fun clipForLook() {
        (panel.root.parent as? ViewGroup)?.clipChildren = !Glass.on
    }

    // ---- Live video placement (LivePage.Host) ---------------------------------------------------------------------

    override fun placeVideo(rect: RectF?, radius: Float, full: Boolean, animateMs: Long) {
        videoTarget = rect?.let { RectF(it) }
        // Captions follow the Subtitle style setting, read again each time full screen opens.
        if (full && !videoFull) captionStyler.apply(captions, SubtitlePresets(Prefs(this)).active())
        videoFull = full
        videoRadius = radius
        layoutVideo(animateMs)
        applyLiveDim()
    }

    /*
     * Moving smoothly (miniature <-> full screen, Android 7 and later, where a SurfaceView follows its view
     * transforms). The surface keeps its layout while the picture moves, and its transforms (scale and translation
     * from its top left) show it on the way, so it's never resized frame by frame; the wallpaper's hole follows it,
     * its corners rounding off or back, and full screen's dimmer fades with it. At the end the surface is laid out
     * where the picture stopped, untransformed: its one resize, made once the picture is exactly there. (Resized at
     * the start instead, the surface would flash at the wrong size for a frame: Fire OS sets a resized surface's
     * place without its transform and only puts the transform back on the next frame.) Captions, full screen's
     * dimmer and Display size's full-screen picture are the end's: they apply when it's reached.
     */
    private val motion = app.fireplayer.live.ui.PictureMotion()
    /** Where the surface is laid out; null while hidden. While the picture moves, its transforms show it elsewhere. */
    private var laidAt: LivePicture.Placement? = null
    private var mover: android.animation.ValueAnimator? = null
    /** Full screen as it shows: reached once a move there has ended. Captions and the dimmer wait for it. */
    private var pictureFull = false
    private val endHole = app.fireplayer.live.ui.PictureMotion.Box()
    private val holeNow = RectF()
    private val transformNow = FloatArray(4)
    /** Screen dimmer's display amount (0 to 1) as last read; it shows as much of it as of full screen. */
    private var dimDisplay = 0f
    /** Fast out, slow in: the picture sets off briskly and settles gently into place. */
    private val moveEasing by lazy { android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f) }
    private val moveFrame = android.animation.ValueAnimator.AnimatorUpdateListener { showMove(it.animatedFraction) }
    private val moveEnd = object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            // A move cancelled for another one: that one carries on from where this one stood.
            if (mover !== animation) return
            mover = null
            layoutVideo()
        }
    }

    /**
     * The surface fits the video's shape inside its target (black bars come from the window behind it). Full screen
     * follows Display size, the VOD player's saved choice ([LivePicture]); a picture bigger than the screen (Fill
     * screen, Zoom) starts above and left of it and the screen crops it.
     *
     * With [animateMs] the picture moves there from where it is over that time (see [motion]). Placed again while
     * it moves (a layout pass, the picture's shape now known), it heads for the new place in the time left.
     */
    private fun layoutVideo(animateMs: Long = 0L) {
        val target = when {
            videoFull -> RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())
            else -> videoTarget
        }
        if (target == null || target.isEmpty) {
            hideVideo()
            return
        }
        val size = player.status().videoSize
        val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        val mode = if (videoFull) videoPrefs.displayMode else PictureFit.NORMAL
        val at = LivePicture.place(target.left, target.top, target.width(), target.height(), aspect, videoFull, mode)
        val radius = if (videoFull) 0f else videoRadius
        endHole.set(target.left, target.top, target.right, target.bottom)
        val running = mover
        // Already on its way there: it carries on undisturbed.
        if (running != null && motion.endsAt(at, endHole, radius, videoFull)) return
        val ms = when {
            animateMs > 0 -> animateMs
            running != null -> maxOf(running.duration - running.currentPlayTime, app.fireplayer.live.ui.PictureMotion.MIN_RETARGET_MS)
            else -> 0L
        }
        val canMove = ms > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            laidAt != null && surface.visibility == View.VISIBLE && !motion.isAt(at, endHole, radius, videoFull)
        if (canMove) {
            startMove(at, radius, ms)
        } else {
            stopMove()
            settleVideo(at, target, radius)
        }
    }

    private fun hideVideo() {
        stopMove()
        motion.clear()
        laidAt = null
        pictureFull = false
        wallpaper.setHole(null, 0f)
        resetTransform()
        if (surface.visibility != View.INVISIBLE) {
            val lp = surface.layoutParams as FrameLayout.LayoutParams
            lp.width = 1; lp.height = 1; lp.leftMargin = 0; lp.topMargin = 0
            surface.layoutParams = lp
            surface.visibility = View.INVISIBLE
        }
        captions.setCues(emptyList())
        captions.visibility = View.GONE
        showDimmer()
    }

    /** The picture at [at] at once (or at the end of a move): laid out there, untransformed, the hole around [target]. */
    private fun settleVideo(at: LivePicture.Placement, target: RectF, radius: Float) {
        motion.settle(at, endHole, radius, videoFull)
        resetTransform()
        val moved = laidAt != at
        if (moved || surface.visibility != View.VISIBLE) {
            val lp = surface.layoutParams as FrameLayout.LayoutParams
            lp.width = at.width
            lp.height = at.height
            lp.leftMargin = at.left
            lp.topMargin = at.top
            lp.gravity = Gravity.TOP or Gravity.START
            surface.layoutParams = lp
        }
        laidAt = at
        surface.visibility = View.VISIBLE
        wallpaper.setHole(target, radius, full = videoFull)
        pictureFull = videoFull
        // The full-screen controls' glass shows a copy of the picture: one of it where it is now.
        if (moved && filmOn) film?.frameChanged()
        // Captions only over the full-screen picture (in the miniature they'd be too small to read), and only over
        // the part of it on screen.
        if (videoFull) {
            val shown = LivePicture.onScreen(at, root.width, root.height)
            captions.layoutParams = FrameLayout.LayoutParams(shown.width, shown.height).apply {
                leftMargin = shown.left; topMargin = shown.top; gravity = Gravity.TOP or Gravity.START
            }
            captions.visibility = View.VISIBLE
            // What's on screen already, not just from the next line on.
            showCues(player.currentCues)
        } else {
            captions.setCues(emptyList())
            captions.visibility = View.GONE
        }
        showDimmer()
    }

    /** The picture sets off from where it is now (mid-move too) towards [at], arriving in [ms]. */
    private fun startMove(at: LivePicture.Placement, radius: Float, ms: Long) {
        stopMove()
        motion.start(at, endHole, radius, videoFull)
        // Captions come back at full screen's end; none on the way.
        pictureFull = false
        captions.setCues(emptyList())
        captions.visibility = View.GONE
        surface.pivotX = 0f
        surface.pivotY = 0f
        val move = android.animation.ValueAnimator.ofFloat(0f, 1f)
        move.duration = ms
        move.interpolator = moveEasing
        move.addUpdateListener(moveFrame)
        move.addListener(moveEnd)
        mover = move
        // Where it starts, in this very frame (no frame shows anything else).
        showMove(0f)
        move.start()
    }

    private fun stopMove() {
        val move = mover ?: return
        mover = null
        move.cancel()
    }

    /** One frame of a move, [f] of the way (eased): the surface's transform, the wallpaper's hole and the dimmer. */
    private fun showMove(f: Float) {
        val laid = laidAt ?: return
        motion.at(f)
        app.fireplayer.live.ui.PictureMotion.transform(laid, motion.picture, transformNow)
        surface.scaleX = transformNow[0]
        surface.scaleY = transformNow[1]
        surface.translationX = transformNow[2]
        surface.translationY = transformNow[3]
        val hole = motion.hole
        holeNow.set(hole.left, hole.top, hole.right, hole.bottom)
        // Not full screen until the end: the wallpaper shows around the picture all the way.
        wallpaper.setHole(holeNow, motion.radius, full = false)
        showDimmer()
    }

    private fun resetTransform() {
        surface.scaleX = 1f
        surface.scaleY = 1f
        surface.translationX = 0f
        surface.translationY = 0f
    }

    /** Full screen's dimmer, as much of it as of full screen shows (all of it there, fading in and out on the way). */
    private fun showDimmer() {
        val alpha = dimDisplay * motion.fullness
        liveDimmer.alpha = alpha
        liveDimmer.visibility = if (alpha > 0f) View.VISIBLE else View.GONE
    }

    /**
     * Screen dimmer, as the VOD player's applyDim: the full-screen live picture and its captions darken by the
     * display amount (never the miniature, the controls or the windows), and glass by the glass amount. Only glass
     * showing the live picture is dimmed ([glassShowsFilm]); the guide's glass over the wallpaper never is.
     */
    private fun applyLiveDim() {
        val amounts = dimmer.amounts()
        dimDisplay = amounts.display / 100f
        showDimmer()
        val glass = amounts.glass / 100f
        if (Glass.dim != glass) {
            Glass.dim = glass
            // Every glass shape reads it as it draws: draw them all again.
            redrawAll(window.decorView)
        }
    }

    private fun redrawAll(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) redrawAll(view.getChildAt(i))
    }

    private var lastSize: VideoSize = VideoSize.UNKNOWN
    private val videoSizeListener = object : PlayerHost.Listener {
        override fun onLiveStatus(status: PlayerHost.Status) {
            // The screen stays on while a channel plays (the Fire TV screensaver would cover it), as films do.
            root.keepScreenOn = status.key != null && status.error == null
            film?.playing = status.playing
            if (status.videoSize != lastSize) {
                lastSize = status.videoSize
                layoutVideo()
            }
        }

        override fun onLiveCues(cues: List<Cue>) = showCues(cues)
    }

    /**
     * Captions over the full-screen picture: text ones in the Subtitle style, picture ones (DVB subtitles) as they
     * come (they have no text to restyle).
     */
    private fun showCues(cues: List<Cue>) {
        if (!pictureFull) { captions.setCues(emptyList()); return }
        val (pictures, text) = cues.partition { it.bitmap != null }
        captions.setCues(captionStyler.normalize(text) + pictures)
    }

    override fun setChromeVisible(visible: Boolean) {
        // Full screen hides the rail; back in the guide, Live TV shows or slides it itself (setRailShown).
        findViewById<View>(R.id.rail_host).visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Live TV lays out its own left side (the rail slides over it, the column and content move), so its page starts at
     * the screen's edge; other tabs start right of the rail's strip.
     */
    private fun pageInset(tab: NavRail.Tab) {
        (pages.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            lp.marginStart = if (tab == NavRail.Tab.LIVE) 0 else rail.collapsedWidth
            pages.layoutParams = lp
        }
    }

    override fun setRailShown(shown: Boolean, animateMs: Long) {
        val host = findViewById<View>(R.id.rail_host)
        host.animate().cancel()
        if (shown) host.visibility = View.VISIBLE
        val to = if (shown) 0f else -host.width.toFloat()
        if (animateMs <= 0) {
            host.translationX = to
            if (!shown) host.visibility = View.INVISIBLE
            return
        }
        // The same easing as the column and the content sliding with it (one motion).
        host.animate().translationX(to).setDuration(animateMs).setInterpolator(moveEasing)
            .withEndAction { if (!shown) host.visibility = View.INVISIBLE }.start()
    }

    override fun focusRail() = rail.focusSelected()

    override fun railHasFocus(): Boolean = rail.hasFocus() || panel.isOpen

    // ---- Keys -----------------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isBack = event.keyCode == KeyEvent.KEYCODE_BACK
        if (!isBack && event.action == KeyEvent.ACTION_DOWN) consumeBackUp = false
        // A Back that closes a dialog: its release (and repeats) must not act again on what's underneath.
        if (isBack && event.action == KeyEvent.ACTION_DOWN && FireDialog.isShowing(this)) consumeBackUp = true
        // A small window (a list of choices, a question) open over the screen takes the keys first.
        if (FireDialog.dispatch(this, event)) return true
        if (isBack && consumeBackUp) {
            if (event.action == KeyEvent.ACTION_UP) consumeBackUp = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && rail.selected == NavRail.Tab.LIVE) live.noteKey()
        // Live TV (full screen, guide gestures) next, unless the settings window is open over it.
        if (!panel.isOpen && rail.selected == NavRail.Tab.LIVE && !rail.hasFocus() && live.dispatchKey(event)) return true
        // Media buttons this screen didn't use stay here. Left to Android, they go to the last app that played
        // something: on the Fire TV, Play/Pause over this screen started a paused Stremio film playing behind it.
        if (event.keyCode in MEDIA_KEYS) return true
        // The menu button (three lines) opens Settings, or Subtitle style as chosen in Settings, as in the player;
        // pressed again, it closes the window.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && ::settingsPage.isInitialized) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (panel.isOpen) panel.close() else openMenuWindow()
            }
            return true
        }
        if (isBack) {
            if (event.action == KeyEvent.ACTION_DOWN && panel.isOpen) {
                consumeBackUp = true
                // A window opened from another (a playlist from the list) goes back to it.
                panel.back()
                return true
            }
            // Back from a page goes to the rail; Back on the rail leaves the app.
            if (!panel.isOpen && !rail.hasFocus()) {
                if (event.action == KeyEvent.ACTION_UP) rail.focusSelected()
                return true
            }
        }
        if (!panel.isOpen) return super.dispatchKeyEvent(event)
        val before = currentFocus
        val handled = super.dispatchKeyEvent(event)
        // Keep focus inside the window while it's open.
        if (panel.isOpen && !panel.root.hasFocus()) {
            if (before != null && before.isAttachedToWindow) before.requestFocus() else panel.focusRow(0)
        }
        return handled
    }

    /**
     * Menu: Settings (App settings' categories; Live FirePlayer Settings over Live TV) or Subtitle style, as chosen,
     * over whichever tab shows. Focus returns to where it was when the window closes; over the Settings tab the page
     * dims behind it, as when a row opens a window.
     */
    private fun openMenuWindow() {
        val style = Prefs(this).menuOpensStyle
        // On the Live TV tab, Settings means Live FirePlayer Settings (user spec).
        if (!style && rail.selected == NavRail.Tab.LIVE) return openPlayerSettings()
        val title = if (style) "Subtitle style" else SettingsCategory.LIST_TITLE
        val render: SidePanel.() -> Unit = {
            if (style) settingsPanels.renderSubtitleStyle(this, onStyleChanged = {}, onAssChanged = {})
            else settingsPanels.renderCategories(this)
        }
        val back = currentFocus
        if (rail.selected == NavRail.Tab.SETTINGS) return openPanel(title, back ?: settingsRows.first(), render)
        panel.open(title, { if (back != null && back.isShown) back.requestFocus() else focusPage() }, render = render)
    }

    companion object {
        /** Link types the player can open; others (rtsp://, rtmp://) would need extra modules. */
        private val PLAYABLE_SCHEMES = setOf("http", "https", "file", "content")
        /** The remote's (and Alexa's) media buttons. */
        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )

        // The Settings tab's sizes, in dp (see settingsColumns).
        private const val ROW_DP = 44
        private const val ROW_GAP_DP = 8
        private const val TITLE_DP = 34
        private const val TITLE_GAP_DP = 16
        private const val SAFE_EDGE_DP = 27
        private const val COLUMN_DP = 240
        private const val ONE_COLUMN_DP = 380
        private const val COLUMN_GAP_DP = 16
    }
}
