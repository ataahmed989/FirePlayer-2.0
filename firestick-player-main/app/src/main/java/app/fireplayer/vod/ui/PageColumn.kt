package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.fireplayer.shell.Tokens

/**
 * A page's header and rows, one under the other: the header band ([headerH], 216 dp on the tab pages, the title page's
 * 302 dp header block), [headerGap] (16 dp; none on the title page), then each row shown (rows that are GONE take no
 * room). Laid out once, and again only when a row appears, goes or changes; the page's Spring then moves it with
 * translationY alone, so the header slides up out of view with the rows. [onRowsMoved] runs after a layout that moved
 * rows, before it's drawn, so the page can keep the chosen row where it was on screen.
 */
@SuppressLint("ViewConstructor")
class PageColumn(
    context: Context,
    private val t: Tokens,
    headerHDp: Float = VodSizes.HEADER_H,
    headerGapDp: Float = VodSizes.HEADER_GAP,
) : ViewGroup(context) {

    /** The header band: Home's header card and window, or the other pages' header card. */
    var header: View? = null
        set(v) {
            field?.let { removeView(it) }
            field = v
            if (v != null) addView(v, 0)
        }

    /** Called after a layout that moved rows (main thread, before drawing). */
    var onRowsMoved: (() -> Unit)? = null

    private val headerH = t.dp(headerHDp)
    private val headerGap = t.dp(headerGapDp)
    private var tops = IntArray(0)

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Top of child [row] (a [TitleRow] in this column) in the column, px; -1 when it's not shown. */
    fun topOf(row: View): Int {
        val i = indexOfChild(row)
        return if (i < 0 || row.visibility == GONE || i >= tops.size) -1 else tops[i]
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var h = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val ch = if (c === header) headerH else (c as? TitleRow)?.bandHeight ?: c.layoutParams.height.coerceAtLeast(0)
            c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            h += ch + if (c === header) headerGap else 0
        }
        // Taller than the viewport it sits in: the parent's limit doesn't apply.
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val now = IntArray(childCount) { -1 }
        var y = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.layout(0, y, c.measuredWidth, y + c.measuredHeight)
            now[i] = y
            y += c.measuredHeight + if (c === header) headerGap else 0
        }
        val moved = !now.contentEquals(tops)
        tops = now
        if (moved) onRowsMoved?.invoke()
    }
}
