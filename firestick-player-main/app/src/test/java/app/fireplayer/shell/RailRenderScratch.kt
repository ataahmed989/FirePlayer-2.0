package app.fireplayer.shell

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassLayer
import app.fireplayer.ui.GlassSource
import app.fireplayer.ui.PageGlass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Scratch (never committed): draws the rail's motions into pictures, to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailRenderScratch {

    class Host : Activity(), GlassSource.Host {
        var behind: PageGlass? = null
        override val glassSource: GlassSource? = null
        override val pageGlass: GlassSource? get() = behind
    }

    private lateinit var root: FrameLayout
    private lateinit var rail: NavRail
    private lateinit var out: File
    private val log = StringBuilder()

    private fun page(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF10151C.toInt(), 0xFF1B2836.toInt(), 0xFF0C1016.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // A banner and cards, bright, starting where a page starts (94 dp), and words.
        p.color = 0xFFD2691E.toInt(); c.drawRoundRect(188f, 88f, 1180f, 520f, 44f, 44f, p)
        p.color = 0xFF2E8B57.toInt(); c.drawRoundRect(188f, 620f, 700f, 910f, 24f, 24f, p)
        p.color = 0xFF4169E1.toInt(); c.drawRoundRect(730f, 620f, 1240f, 910f, 24f, 24f, p)
        p.color = Color.WHITE; p.textSize = 96f; c.drawText("The Odyssey", 240f, 240f, p)
        p.textSize = 44f; c.drawText("After the Trojan War, Odysseus faces a dangerous", 240f, 330f, p)
        c.drawText("Continue Watching", 188f, 590f, p)
        // Something bright right under the collapsed rail too (a title page's backdrop reaches there).
        p.color = 0xFFE0C040.toInt(); c.drawRect(0f, 940f, 400f, 1080f, p)
        return b
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS) else shadowOf(Looper.getMainLooper()).idle()
    }

    private fun value(name: String): Float {
        val f = NavRail::class.java.getDeclaredField(name).apply { isAccessible = true }
        return (f.get(rail) as RailMotion).value
    }

    private fun shot(name: String) {
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        val crop = Bitmap.createBitmap(b, 0, 0, 560, root.height)
        FileOutputStream(File(out, "$name.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log.append("$name wide=${value("widen")} attach=${value("attach")}\n")
    }

    private fun frames(name: String, n: Int) {
        for (i in 1..n) {
            idle(17)
            shot("$name-%02d".format(i))
        }
    }

    @Test fun render() {
        val dir = System.getProperty("rail.out") ?: ""
        if (dir.isEmpty()) return
        for (glass in listOf(true, false)) {
            Glass.on = glass
            out = File(dir, if (glass) "glass" else "classic").apply { mkdirs() }
            val controller = Robolectric.buildActivity(Host::class.java).setup()
            val a = controller.get()
            root = FrameLayout(a)
            val layer = GlassLayer(a)
            layer.addView(View(a).apply { background = BitmapDrawable(a.resources, page(1920, 1080)) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.addView(layer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val pg = PageGlass(root, listOf(layer)) { 2 }
            a.behind = pg
            layer.onContentChanged = { pg.contentChanged(it) }
            rail = NavRail(a, Tokens(a))
            val host = FrameLayout(a)
            host.addView(rail, FrameLayout.LayoutParams(rail.viewWidth, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            a.setContentView(root)
            idle()
            log.append("root ${root.width}x${root.height} rail ${rail.width}x${rail.height} laidOut=${rail.isLaidOut} shown=${rail.isShown}\n")

            // At rest, attached (Live TV) and floating.
            rail.setSelected(NavRail.Tab.HOME)
            shot("a-rest-strip")
            rail.setAttached(false)
            idle()
            shot("b-rest-window")

            // Widening on a floating tab, and collapsing.
            rail.focusSelected()
            rail.setExpanded(true)
            frames("c-widen", 20)
            idle(600)
            shot("d-wide-rest")
            root.requestFocus()
            rail.clearFocus()
            rail.setExpanded(false)
            frames("e-collapse", 20)

            // Live TV entered and left with the rail narrow.
            rail.setAttached(true, animate = true)
            frames("f-attach", 20)
            rail.setAttached(false, animate = true)
            frames("g-detach", 20)

            // Live TV opened from the widened window: it collapses as it becomes the strip. And back.
            rail.focusSelected()
            rail.setExpanded(true)
            idle(600)
            rail.setSelected(NavRail.Tab.LIVE)
            rail.setAttached(true, animate = true)
            rail.clearFocus()
            rail.setExpanded(false)
            frames("h-into-live", 20)
            rail.focusSelected()
            rail.setExpanded(true)
            idle(600)
            shot("i-wide-strip-rest")
            rail.setSelected(NavRail.Tab.HOME)
            rail.setAttached(false, animate = true)
            rail.clearFocus()
            rail.setExpanded(false)
            frames("j-out-of-live", 20)

            // Turned round part-way.
            rail.focusSelected()
            rail.setExpanded(true)
            frames("k-turn", 4)
            rail.clearFocus()
            rail.setExpanded(false)
            frames("k-turn-back", 12)
            controller.pause().stop().destroy()
        }
        File(dir, "log.txt").writeText(log.toString())
    }
}
