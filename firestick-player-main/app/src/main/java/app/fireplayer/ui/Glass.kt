package app.fireplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import app.fireplayer.R

/**
 * The optional glass look (Settings, Look, Glass look): buttons, chips, rows and windows become clear glass
 * showing the blurred film behind them, with a light rim, while the layout, icons, text and colours stay as
 * they are. The date, time and seek line never get glass. Fire OS can't blur what's behind a view, so the
 * player keeps a small blurred copy of the film ([GlassBackdrop]) that each glass shape ([GlassDrawable]) shows.
 */
object Glass {
    @Volatile var on = false

    /**
     * How much every piece of glass is darkened (Screen dimmer, a custom's glass amount): 0 not at all, 0.8 to a
     * fifth of its light. The film inside and the milky glaze dim; the selected look dims half as much, so it
     * still stands out.
     */
    @Volatile var dim = 0f

    /** The shapes that can be glass. Each replaces one classic background ([classicRes]). */
    enum class Kind { PANEL, POPUP, DIALOG_POPUP, CIRCLE, PILL, CHIP, MORE_ICON, ROW }

    /** The background each shape has with the glass look off. */
    fun classicRes(kind: Kind): Int = when (kind) {
        Kind.PANEL -> R.drawable.bg_panel
        Kind.POPUP, Kind.DIALOG_POPUP -> R.drawable.bg_popup
        Kind.CIRCLE -> R.drawable.bg_circle
        Kind.PILL -> R.drawable.bg_pill
        Kind.CHIP -> R.drawable.bg_chip
        Kind.MORE_ICON -> R.drawable.bg_more_icon
        Kind.ROW -> R.drawable.bg_row
    }

    /**
     * Glass for [view] when the look is on, otherwise its classic background ([classic], which a shape that
     * looks different when classic, such as the pill buttons in a popup, can name). The view keeps its padding.
     */
    fun background(view: View, kind: Kind, classic: Int = classicRes(kind)) {
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        if (on) view.background = drawable(view.context, kind) else view.setBackgroundResource(classic)
        view.setPadding(l, t, r, b)
    }

    /**
     * The corner radius, px, a [kind] surface [w] x [h] px has in the look now on: the glass shape's own
     * ([GlassBakes.radius]) or the classic drawable's (res/drawable/bg_*.xml: panels and popups 15 dp, pills 20 dp,
     * chips 5 dp, rows 9 dp, circles round; never more than half the shorter side). For something drawn to follow a
     * surface's corners (a header's picture inside its card).
     */
    fun cornerRadius(kind: Kind, w: Int, h: Int, density: Float): Float {
        if (on) return GlassBakes.radius(kind, w, h, density)
        val half = minOf(w, h) / 2f
        return when (kind) {
            Kind.PANEL, Kind.POPUP, Kind.DIALOG_POPUP -> minOf(15f * density, half)
            Kind.PILL -> minOf(20f * density, half)
            Kind.CIRCLE, Kind.MORE_ICON -> half
            Kind.CHIP -> minOf(5f * density, half)
            Kind.ROW -> minOf(9f * density, half)
        }
    }

    /** A new glass background of this shape, whatever the setting. Rows have no film of their own. */
    fun drawable(context: Context, kind: Kind): Drawable =
        GlassDrawable(context, kind, if (kind == Kind.ROW) null else backdropFor(context))

    /**
     * Whether [context] is the player's, or a screen whose glass shows a live picture just now (Home's full-screen
     * live controls, [GlassSource.Host.glassShowsFilm]): glass made there is dimmed by the screen dimmer. Home's glass
     * over the wallpaper never is.
     */
    fun inPlayer(context: Context): Boolean {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c is GlassBackdrop.Host || (c as? GlassSource.Host)?.glassShowsFilm == true
            c = c.baseContext
        }
        return false
    }

    /**
     * The blurred film of the player this view belongs to, the blurred wallpaper of the app's own screens
     * ([GlassSource.Host]), or null where there's neither: glass is then dark.
     */
    fun backdropFor(context: Context): GlassSource? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return (c as? GlassBackdrop.Host)?.glassBackdrop ?: (c as? GlassSource.Host)?.glassSource
            c = c.baseContext
        }
        return null
    }

    /** The switch shown for an on/off setting in place of the word. */
    fun toggle(context: Context, on: Boolean): Drawable = GlassToggleDrawable(context, on)

    // The first glass look's resources, until every caller uses the drawables above.
    val circle get() = if (on) R.drawable.glass_circle else R.drawable.bg_circle
    val pill get() = if (on) R.drawable.glass_pill else R.drawable.bg_pill
    val chip get() = if (on) R.drawable.glass_chip else R.drawable.bg_chip
    val moreIcon get() = if (on) R.drawable.glass_more_icon else R.drawable.bg_more_icon
    val row get() = if (on) R.drawable.glass_row else R.drawable.bg_row
    val panel get() = if (on) R.drawable.glass_panel else R.drawable.bg_panel
    val popup get() = if (on) R.drawable.glass_popup else R.drawable.bg_popup
    val previewEdge get() = if (on) R.drawable.glass_preview_fg else R.drawable.fg_preview
}
