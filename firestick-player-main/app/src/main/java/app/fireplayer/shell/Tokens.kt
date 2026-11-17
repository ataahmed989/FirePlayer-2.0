package app.fireplayer.shell

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The shared look of the app's own screens (shell, guide, live controls), taken from the original player: Figtree,
 * off-white text, blue #3D8BFF accent, dark panels #0B0E12 and rows #1A1E24 (res/values/colors.xml). Classic and
 * glass use these same sizes; only surface materials differ ([app.fireplayer.ui.Glass]).
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

    companion object {
        /** Animation lengths, from the original player: 140 ms state fades, 180 ms panel slides. */
        const val FADE_MS = 140L
        const val SLIDE_MS = 180L
        /** Focus growth (original player's focusEffect): circles 1.08, pills 1.04. */
        const val GROW_CIRCLE = 1.08f
        const val GROW_PILL = 1.04f

        /** Scales a view up while it has focus, eased, as the player's buttons do. */
        fun growOnFocus(view: View, scale: Float) {
            val previous = view.onFocusChangeListener
            view.setOnFocusChangeListener { v, has ->
                previous?.onFocusChange(v, has)
                v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(FADE_MS).start()
            }
        }
    }
}
