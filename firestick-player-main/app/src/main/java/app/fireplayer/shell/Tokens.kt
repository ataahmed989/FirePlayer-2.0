package app.fireplayer.shell

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The shared look of the app's own screens (shell, guide, live controls, Films & Shows), taken from the original
 * player: Figtree, off-white text, blue #3D8BFF accent, dark panels #0B0E12 and rows #1A1E24 (res/values/colors.xml).
 * Classic and glass use these same sizes; only surface materials differ ([app.fireplayer.ui.Glass]). Films & Shows'
 * own sizes and text styles are in VodSizes, which reads its colours from here.
 *
 * Focus rule (design-layout): a focused item is a white body with blue text and icons; the playing channel stays
 * blue when it isn't focused, so "playing" and "focused" never look the same.
 */
class Tokens(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())
    fun dpf(v: Float): Float = v * density

    val accent = ContextCompat.getColor(context, R.color.accent)
    val text = ContextCompat.getColor(context, R.color.text)
    val textMuted = ContextCompat.getColor(context, R.color.text_muted)
    val focusFill = ContextCompat.getColor(context, R.color.focus_fill)
    val rowFill = ContextCompat.getColor(context, R.color.row_fill)
    val panelFill = ContextCompat.getColor(context, R.color.panel_fill)
    val progressTrack = ContextCompat.getColor(context, R.color.progress_track)
    /** Headings in windows (settings, Home's recent channels) and their line icons. */
    val heading = ContextCompat.getColor(context, R.color.heading)
    val headingIcon = ContextCompat.getColor(context, R.color.heading_icon)
    /** A flat surface in the glass look (grouped rows' card; chips and See all cards in a moving row) and its rim. */
    val groupFillGlass = ContextCompat.getColor(context, R.color.group_fill_glass)
    val groupRimGlass = ContextCompat.getColor(context, R.color.group_rim_glass)
    /** A button's progress line (Resume): its track, plain and on the white selected button. */
    val buttonTrack = ContextCompat.getColor(context, R.color.button_track)
    val buttonTrackFocused = ContextCompat.getColor(context, R.color.button_track_focused)

    val regular: Typeface = ResourcesCompat.getFont(context, R.font.figtree_500) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.figtree_600) ?: Typeface.DEFAULT_BOLD
    val bold: Typeface = ResourcesCompat.getFont(context, R.font.figtree_700) ?: Typeface.DEFAULT_BOLD

    fun label(context: Context, sizeSp: Float, face: Typeface = regular, color: Int = text): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = face
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** A one-line label in [style] (as [label] above); a style's line gap is added between its lines. */
    fun label(context: Context, style: TextStyle): TextView = label(context, style.sp, faceOf(style.weight), colorOf(style)).apply {
        if (style.lineGap != 0f) setLineSpacing(dpf(style.lineGap), 1f)
    }

    fun faceOf(weight: TextStyle.Weight): Typeface = when (weight) {
        TextStyle.Weight.REGULAR -> regular
        TextStyle.Weight.SEMIBOLD -> semibold
        TextStyle.Weight.BOLD -> bold
    }

    fun colorOf(style: TextStyle): Int = when (style.ink) {
        TextStyle.Ink.TEXT -> text
        TextStyle.Ink.MUTED -> textMuted
        TextStyle.Ink.ACCENT -> accent
        TextStyle.Ink.OWN -> style.color
    }

    companion object {
        /** Animation lengths, from the original player: 140 ms state fades, 180 ms panel slides. */
        const val FADE_MS = 140L
        const val SLIDE_MS = 180L
        /** Focus growth (original player's focusEffect): circles 1.08, pills 1.04. */
        const val GROW_CIRCLE = 1.08f
        const val GROW_PILL = 1.04f

        private val FOCUS_EASE = DecelerateInterpolator(1.4f)

        /**
         * The app's focus growth (the original player's focusEffect): [button] grows to [scale] while it has focus,
         * eased over [FADE_MS]; [label] (a round button's name under it) fades in and grows with it, and hides again
         * when focus leaves. Live TV's controls and the title page's buttons use it. [also] runs on each change.
         */
        fun focusEffect(button: View, scale: Float, label: View?, also: ((has: Boolean) -> Unit)? = null) {
            button.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) scale else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(FADE_MS).setInterpolator(FOCUS_EASE).start()
                if (label != null) {
                    label.pivotX = v.left + v.width / 2f - label.left
                    label.pivotY = v.top + v.height / 2f - label.top
                    if (hasFocus) label.visibility = View.VISIBLE
                    label.animate().alpha(if (hasFocus) 1f else 0f).scaleX(s).scaleY(s).setDuration(FADE_MS).setInterpolator(FOCUS_EASE)
                        .withEndAction { if (!v.isFocused) label.visibility = View.INVISIBLE }
                        .start()
                }
                also?.invoke(hasFocus)
            }
        }
    }
}
