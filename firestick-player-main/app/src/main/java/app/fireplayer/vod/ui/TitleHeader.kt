package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.ImageTarget

/**
 * The title page's header block (design-brief.md §6.4, layout A), at fixed sizes so new words only redraw it: the
 * title (its logo, or its name in 32 sp, 24 sp when long), the facts line with at most one callout chip beside it,
 * three lines of story, one cast line, the source line (kept empty until step 3 fills it, its height reserved so the
 * buttons never move), then the buttons ([TitleButtons]). Its text styles are the page headers' ([VodSizes]); while an
 * episode card is chosen it shows that episode's facts and overview instead ([follow]), the title and backdrop staying.
 */
@SuppressLint("ViewConstructor")
class TitleHeader(context: Context, private val t: Tokens, buttonsListener: TitleButtons.Listener) : LinearLayout(context) {

    /** The title's logo, drawn whole inside its box, at its left and bottom. */
    class TitleLogo(context: Context) : View(context), ImageTarget {
        override var imageKey: String? = null
        var bitmap: Bitmap? = null
            private set
        /** Called once a picture arrives (main thread). */
        var onArrived: ((Bitmap) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
            this.bitmap = bitmap
            invalidate()
            if (bitmap != null) onArrived?.invoke(bitmap)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            if (width <= 0 || height <= 0) return
            val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
            val w = b.width * s
            val h = b.height * s
            dst.set(0f, height - h, w, height.toFloat())
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private val titleBox = FrameLayout(context)
    val logo = TitleLogo(context)
    private val name = t.label(context, VodSizes.TITLE_NAME).apply { gravity = Gravity.BOTTOM or Gravity.START }
    private val factsRow = FrameLayout(context)
    private val facts = t.label(context, VodSizes.FACTS).apply { gravity = Gravity.CENTER_VERTICAL }
    private val callout: TextView = Buttons.infoChip(context, t, VodSizes.CALLOUT, VodSizes.CALLOUT_PAD)
    private val story = t.label(context, VodSizes.STORY).apply { setLines(VodSizes.STORY_LINES) }
    private val cast = t.label(context, VodSizes.CAST)
    /** Where step 3 says which add-on answered; empty now, its height kept. */
    val source = t.label(context, VodSizes.SOURCE)
    val buttons = TitleButtons(context, t, buttonsListener)

    private val factsW = t.dp(VodSizes.tabHeaderW())
    private var showFacts = ""
    private var showStory = ""
    private var showCallout: String? = null
    private var following = false

    init {
        orientation = VERTICAL
        // Glass pads and the circles' names draw outside the views.
        clipChildren = false
        clipToPadding = false
        titleBox.addView(logo, FrameLayout.LayoutParams(t.dp(VodSizes.LOGO_W), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.START))
        titleBox.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        logo.visibility = INVISIBLE
        name.visibility = INVISIBLE
        addView(titleBox, LayoutParams(t.dp(VodSizes.TITLE_BOX_W), t.dp(VodSizes.TITLE_BOX_H)).apply { topMargin = t.dp(VodSizes.TITLE_TOP) })

        factsRow.clipChildren = false
        factsRow.addView(facts, FrameLayout.LayoutParams(factsW, FrameLayout.LayoutParams.MATCH_PARENT))
        factsRow.addView(callout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        callout.visibility = INVISIBLE
        addView(factsRow, LayoutParams(factsW, t.dp(VodSizes.FACTS_ROW_H)).apply { topMargin = t.dp(VodSizes.FACTS_GAP) })

        val textW = t.dp(VodSizes.TITLE_TEXT_W)
        addView(story, LayoutParams(textW, story.lineHeight * VodSizes.STORY_LINES).apply { topMargin = t.dp(VodSizes.STORY_GAP) })
        addView(cast, LayoutParams(textW, cast.lineHeight).apply { topMargin = t.dp(VodSizes.CAST_GAP) })
        addView(source, LayoutParams(factsW, source.lineHeight).apply { topMargin = t.dp(VodSizes.SOURCE_GAP) })
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.BUTTONS_GAP_ABOVE) })
    }

    /** The name in the title box: 32 sp, or 24 sp on one line (cut short) when it's wider than the box at 32 sp. */
    fun showName(text: String) {
        if (name.visibility == VISIBLE && name.text.toString() == text) return
        val full = name.paint.let { p ->
            val size = p.textSize
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, VodSizes.TITLE_NAME.sp, resources.displayMetrics)
            val w = p.measureText(text)
            p.textSize = size
            w
        }
        val style = if (full > t.dpf(VodSizes.TITLE_BOX_W)) VodSizes.TITLE_NAME_LONG else VodSizes.TITLE_NAME
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        name.text = text
        name.contentDescription = text
        logo.visibility = INVISIBLE
        name.visibility = VISIBLE
    }

    /** The logo in the title box (its name still read out). */
    fun showLogo(text: String) {
        name.visibility = INVISIBLE
        logo.contentDescription = text
        logo.visibility = VISIBLE
    }

    /** The title's facts, its callout (or none), story and cast line. While following an episode, kept for later. */
    fun show(factsLine: String, calloutText: String?, storyText: String, castLine: String) {
        showFacts = factsLine
        showStory = storyText
        showCallout = calloutText
        if (cast.text.toString() != castLine) cast.text = castLine
        if (!following) applyTitleText()
    }

    /** The story as it shows for the title (to tell whether a later one may replace it). */
    val storyShown: String get() = showStory

    /** An episode card is chosen: its facts and overview; the callout and cast line hide (their room kept). */
    fun follow(factsLine: String, overview: String) {
        following = true
        setFacts(factsLine)
        if (story.text.toString() != overview) story.text = overview
        callout.visibility = INVISIBLE
        cast.visibility = INVISIBLE
    }

    /** Back to the title's own words. */
    fun unfollow() {
        if (!following) return
        following = false
        cast.visibility = VISIBLE
        applyTitleText()
    }

    private fun applyTitleText() {
        setFacts(showFacts)
        if (story.text.toString() != showStory) story.text = showStory
        val c = showCallout
        if (c.isNullOrEmpty()) {
            callout.visibility = INVISIBLE
        } else {
            if (callout.text.toString() != c) callout.text = c
            callout.visibility = VISIBLE
        }
        placeCallout()
    }

    /** The facts line, cut to its width once here (so the line never lays anything out again). */
    private fun setFacts(line: String) {
        val fitted = android.text.TextUtils.ellipsize(line, facts.paint, factsW.toFloat(), android.text.TextUtils.TruncateAt.END)
        if (facts.text.toString() != fitted.toString()) facts.text = fitted
        placeCallout()
    }

    /** The chip sits 10 dp after the facts' words (moved, not laid out). */
    private fun placeCallout() {
        val w = facts.paint.measureText(facts.text.toString())
        callout.translationX = w + t.dpf(VodSizes.CALLOUT_GAP)
    }

    /** The buttons' top in this block, px (where the page glides to on a show). */
    fun buttonsTop(): Int = buttons.top

    fun applyLook() {
        app.fireplayer.ui.Glass.background(callout, app.fireplayer.ui.Glass.Kind.CHIP)
        buttons.applyLook()
    }
}
