package app.fireplayer.vod.ui

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.shell.NavRail
import app.fireplayer.vod.Page
import app.fireplayer.vod.VodRepositories
import java.util.EnumMap

/**
 * The shell's Films & Shows tabs (Home, Movies, TV Shows): a [VodPage] for each, made the first time its tab opens, in
 * the shell's page container. Only the selected one is shown and running; the others are hidden with their timers
 * stopped and pictures let go. The pages get their titles from [VodRepositories].
 */
class VodPages(private val context: Context, private val host: VodPage.Host, private val container: FrameLayout) {

    private val pages = EnumMap<Page, VodPage>(Page::class.java)
    private var started = false

    /** The page shown, or null on another tab. */
    var current: VodPage? = null
        private set

    fun isVodTab(tab: NavRail.Tab): Boolean = pageOf(tab) != null

    /** Shows [tab]'s page (hiding the others), or only hides them for a tab that isn't one. */
    fun show(tab: NavRail.Tab) {
        val p = pageOf(tab)
        for ((k, page) in pages) if (k != p) page.hide()
        if (p == null) {
            current = null
            return
        }
        val page = pages.getOrPut(p) {
            VodPage(context, host, { VodRepositories.get(context) }, p).also {
                container.addView(it.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (started) it.start()
            }
        }
        current = page
        page.show()
    }

    fun start() {
        started = true
        for (page in pages.values) page.start()
    }

    fun stop() {
        started = false
        for (page in pages.values) page.stop()
    }

    fun release() {
        for (page in pages.values) page.release()
    }

    fun applyLook() {
        for (page in pages.values) page.applyLook()
    }

    /** Memory is short: the shown page keeps only its chosen row's pictures (hidden pages hold none). */
    fun trimImages() {
        current?.trimImages()
    }

    /** The shown page's keys (Back for its small window and See all). */
    fun dispatchKey(event: KeyEvent): Boolean = current?.dispatchKey(event) ?: false

    private fun pageOf(tab: NavRail.Tab): Page? = when (tab) {
        NavRail.Tab.HOME -> Page.HOME
        NavRail.Tab.MOVIES -> Page.MOVIES
        NavRail.Tab.SHOWS -> Page.SHOWS
        else -> null
    }
}
