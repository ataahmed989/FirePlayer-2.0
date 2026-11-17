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
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.update.Updater

/**
 * What opens from the Fire TV home screen: the app shell. A tab rail (one FirePlayer mark; only tabs that work are
 * shown) and its pages: Live TV (guide, miniature and full-screen player) and Settings, which keeps everything the
 * old home menu had — Open a link, Subtitle style and the same Settings windows as the player — beside the new
 * Playlists, EPG and Live TV settings.
 *
 * Both looks share this layout; glass shows the stationary wallpaper blurred ([GlassSource.Host]) and classic the
 * original dark surfaces over the same wallpaper.
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
    private lateinit var styleRow: View
    private lateinit var settingsRow: View
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

        wallpaper = WallpaperView(this)
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
        pages.addView(settingsPage, FrameLayout.LayoutParams(tokens.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { leftMargin = tokens.dp(40) })

        player.attach(surface)
        player.addListener(videoSizeListener)

        lookOn = Glass.on
        clipForLook()
        settingsPanels.onLookChanged = { applyLook() }
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
        // Playlists not updated within their interval are updated in the background.
        LiveSync.get(this).updateDue()
    }

    override fun onStart() {
        super.onStart()
        // This screen's surface shows live again (another instance may have taken it meanwhile).
        player.attach(surface)
        if (rail.selected == NavRail.Tab.LIVE) live.start()
        // The dimmer may have been changed in the VOD player meanwhile.
        applyLiveDim()
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
            NavRail.Tab.SETTINGS -> (settingsPage.getChildAt(1) as? ViewGroup)?.getChildAt(0)?.requestFocus()
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

    private lateinit var settingsList: LinearLayout

    private fun buildSettingsPage(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = "Settings"
            typeface = tokens.bold
            setTextColor(tokens.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = tokens.dp(16) })
        settingsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(settingsList)
        addRow("Playlists", R.drawable.ic_playlist_add) { from -> liveSettings.openPlaylists(onClose = { restore(from) }).also { dim() } }
        addRow("EPG", R.drawable.ic_epg) { from -> liveSettings.openEpg(onClose = { restore(from) }).also { dim() } }
        addRow("Live TV", R.drawable.ic_live) { from -> liveSettings.openLiveOptions(onClose = { restore(from) }).also { dim() } }
        addRow("Open a link", R.drawable.ic_link) { openLink() }
        styleRow = addRow("Subtitle style", R.drawable.ic_sub_style) { from ->
            openPanel("Subtitle style", from) {
                settingsPanels.renderSubtitleStyle(this, onStyleChanged = {}, onAssChanged = {})
            }
        }
        settingsRow = addRow("Settings", R.drawable.ic_settings) { from ->
            openPanel("Settings", from) { settingsPanels.renderSettings(this) }
        }
        return column
    }

    private fun dim() = settingsPage.animate().alpha(0.35f).setDuration(160).start()

    private fun restore(from: View) {
        settingsPage.animate().alpha(1f).setDuration(160).start()
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
    override fun openPlayerSettings() {
        val back = currentFocus
        liveSettings.openPlayerSettings(settingsPanels, onClose = { if (back != null && back.isShown) back.requestFocus() else focusPage() })
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

    private fun addRow(label: String, icon: Int, onClick: (View) -> Unit): View {
        val density = resources.displayMetrics.density
        val colors = ContextCompat.getColorStateList(this, R.color.sel_text_row)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            Glass.background(this, Glass.Kind.ROW)
            minimumHeight = (44 * density).toInt()
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
        })
        row.setOnClickListener { onClick(it) }
        // Up from the top row goes to the bottom one and the other way round, like the settings lists.
        row.setOnKeyListener { v, keyCode, event -> Rows.verticalNav(settingsList, v, keyCode, event) }
        settingsList.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * density).toInt() },
        )
        return row
    }

    // ---- Look -----------------------------------------------------------------------------------------------------

    /** Gives the shell the look Settings now has (the settings window redraws its own). */
    private fun applyLook() {
        lookOn = Glass.on
        for (i in 0 until settingsList.childCount) Glass.background(settingsList.getChildAt(i), Glass.Kind.ROW)
        rail.applyLook()
        live.applyLook()
        root.jumpDrawablesToCurrentState()
        clipForLook()
    }

    /** The glass settings window's shadow falls outside it, which this screen would otherwise cut off. */
    private fun clipForLook() {
        (panel.root.parent as? ViewGroup)?.clipChildren = !Glass.on
    }

    // ---- Live video placement (LivePage.Host) ---------------------------------------------------------------------

    override fun placeVideo(rect: RectF?, radius: Float, full: Boolean) {
        videoTarget = rect?.let { RectF(it) }
        // Captions follow the Subtitle style setting, read again each time full screen opens.
        if (full && !videoFull) captionStyler.apply(captions, SubtitlePresets(Prefs(this)).active())
        videoFull = full
        videoRadius = radius
        layoutVideo()
        applyLiveDim()
    }

    /**
     * The surface fits the video's shape inside its target (black bars come from the window behind it). Full screen
     * follows Display size, the VOD player's saved choice ([LivePicture]); a picture bigger than the screen (Fill
     * screen, Zoom) starts above and left of it and the screen crops it.
     */
    private fun layoutVideo() {
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        val target = when {
            videoFull -> RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())
            else -> videoTarget
        }
        if (target == null || target.isEmpty) {
            wallpaper.setHole(null, 0f)
            lp.width = 1; lp.height = 1; lp.leftMargin = 0; lp.topMargin = 0
            surface.layoutParams = lp
            surface.visibility = View.INVISIBLE
            captions.visibility = View.GONE
            return
        }
        val size = player.status().videoSize
        val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        val mode = if (videoFull) videoPrefs.displayMode else PictureFit.NORMAL
        val at = LivePicture.place(target.left, target.top, target.width(), target.height(), aspect, videoFull, mode)
        lp.width = at.width
        lp.height = at.height
        lp.leftMargin = at.left
        lp.topMargin = at.top
        lp.gravity = Gravity.TOP or Gravity.START
        surface.layoutParams = lp
        surface.visibility = View.VISIBLE
        wallpaper.setHole(target, if (videoFull) 0f else videoRadius, full = videoFull)
        // Captions only over the full-screen picture (in the miniature they'd be too small to read), and only over
        // the part of it on screen.
        if (videoFull) {
            val shown = LivePicture.onScreen(at, root.width, root.height)
            captions.layoutParams = FrameLayout.LayoutParams(shown.width, shown.height).apply {
                leftMargin = shown.left; topMargin = shown.top; gravity = Gravity.TOP or Gravity.START
            }
            captions.visibility = View.VISIBLE
        } else {
            captions.setCues(emptyList())
            captions.visibility = View.GONE
        }
    }

    /**
     * Screen dimmer, as the VOD player's applyDim: the full-screen live picture and its captions darken by the
     * display amount (never the miniature, the controls or the windows), and glass by the glass amount. Only glass
     * showing the live picture is dimmed ([glassShowsFilm]); the guide's glass over the wallpaper never is.
     */
    private fun applyLiveDim() {
        val amounts = dimmer.amounts()
        liveDimmer.alpha = amounts.display / 100f
        liveDimmer.visibility = if (videoFull && amounts.display > 0) View.VISIBLE else View.GONE
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

        override fun onLiveCues(cues: List<Cue>) {
            captions.setCues(if (videoFull) captionStyler.normalize(cues) else emptyList())
        }
    }

    override fun setChromeVisible(visible: Boolean) {
        findViewById<View>(R.id.rail_host).visibility = if (visible) View.VISIBLE else View.GONE
        // Full screen: the page (and the live controls in it) spans the whole screen, not just right of the rail.
        (pages.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            lp.marginStart = if (visible) tokens.dp(76) else 0
            pages.layoutParams = lp
        }
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
        // The menu button (three lines) opens Settings, or Subtitle style as chosen in Settings, as in the player;
        // pressed again, it closes the window.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && ::settingsRow.isInitialized) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (panel.isOpen) panel.close() else openMenuWindow()
            }
            return true
        }
        if (isBack) {
            if (event.action == KeyEvent.ACTION_DOWN && panel.isOpen) {
                consumeBackUp = true
                panel.close()
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
     * Menu: Settings (or Subtitle style, as chosen) over whichever tab shows. From the Settings tab it opens from its
     * row; elsewhere focus returns to where it was when the window closes.
     */
    private fun openMenuWindow() {
        val style = Prefs(this).menuOpensStyle
        if (rail.selected == NavRail.Tab.SETTINGS) {
            (if (style) styleRow else settingsRow).performClick()
            return
        }
        // On the Live TV tab, Settings means Live FirePlayer Settings (user spec).
        if (!style && rail.selected == NavRail.Tab.LIVE) return openPlayerSettings()
        val back = currentFocus
        val onClose: () -> Unit = { if (back != null && back.isShown) back.requestFocus() else focusPage() }
        if (style) panel.open("Subtitle style", onClose) { settingsPanels.renderSubtitleStyle(this, onStyleChanged = {}, onAssChanged = {}) }
        else panel.open("Settings", onClose) { settingsPanels.renderSettings(this) }
    }

    companion object {
        /** Link types the player can open; others (rtsp://, rtmp://) would need extra modules. */
        private val PLAYABLE_SCHEMES = setOf("http", "https", "file", "content")
    }
}
