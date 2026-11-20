package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.live.ui.PipRules
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Glass

/**
 * The live picture's box on Home and Search ([PipRules]). The picture itself is the shell's live surface, behind the
 * window, showing through the wallpaper's hole over this box; the box draws only the glass outline over it (glass
 * look) and, at its bottom, the slim strip: the channel and what's on ([setInfo]), and Volume, Full screen and Close.
 *
 * The strip shows only while one of its buttons has focus: it eases in (alpha and a small rise, drawn as one layer)
 * as focus comes ([takeFocus]: on Volume) and out as it leaves. Hidden, its buttons can't take focus, so focus never
 * wanders into an unseen strip. The arrows move along the buttons and out of the strip ([onOut]); OK acts on its
 * release ([onButton]). Glass in the glass look (the app's pill and round buttons), the solid ones in Classic.
 */
@SuppressLint("ViewConstructor")
class PipBox(context: Context, private val t: Tokens) : FrameLayout(context) {

    /** OK released on one of the strip's buttons. */
    var onButton: ((PipRules.Button) -> Unit)? = null
    /** An arrow out of the strip ([PipRules.Step.OutLeft] or [PipRules.Step.OutDown]). */
    var onOut: ((PipRules.Step) -> Unit)? = null
    /** The strip began to show (focus came to it): its words may be read again. */
    var onStripShown: (() -> Unit)? = null

    private val strip = LinearLayout(context)
    private val words = t.label(context, VodSizes.PIP_WORDS).apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val buttons = PipRules.Button.entries.associateWith { ImageView(context) }
    private var stripOn = false
    private var okDown: PipRules.Button? = null
    private var muted = false
    private val rise = t.dpf(VodSizes.PIP_STRIP_RISE)
    private val stripCheck = Runnable { if (!hasFocus()) showStrip(false) }

    init {
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.clipChildren = false
        strip.clipToPadding = false
        strip.setPadding(t.dp(VodSizes.PIP_STRIP_PAD_L), 0, t.dp(VodSizes.PIP_STRIP_PAD_R), 0)
        strip.addView(words, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = t.dp(VodSizes.PIP_WORDS_GAP)
        })
        val size = t.dp(VodSizes.PIP_BUTTON)
        val pad = t.dp((VodSizes.PIP_BUTTON - VodSizes.PIP_ICON) / 2f)
        for ((b, v) in buttons) {
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            v.setPadding(pad, pad, pad, pad)
            v.imageTintList = Buttons.buttonColors(context)
            v.isFocusable = false
            v.isClickable = false
            v.setOnKeyListener { _, keyCode, event -> onKey(b, keyCode, event) }
            Tokens.focusEffect(v, Tokens.GROW_CIRCLE, null) { has ->
                // Leaving one button for another isn't leaving the strip: it's checked once focus has landed.
                removeCallbacks(stripCheck)
                if (has) showStrip(true) else post(stripCheck)
            }
            strip.addView(v, LinearLayout.LayoutParams(size, size).apply {
                if (b.ordinal > 0) leftMargin = t.dp(VodSizes.PIP_BUTTON_GAP)
            })
        }
        buttons[PipRules.Button.FULL_SCREEN]?.apply {
            setImageResource(R.drawable.ic_fullscreen)
            contentDescription = "Full screen"
        }
        buttons[PipRules.Button.CLOSE]?.apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close"
        }
        setMuted(false)
        val inset = t.dp(VodSizes.PIP_STRIP_INSET)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(VodSizes.PIP_STRIP_H), Gravity.BOTTOM).apply {
            leftMargin = inset
            rightMargin = inset
            bottomMargin = inset
        })
        strip.visibility = INVISIBLE
        strip.alpha = 0f
        applyLook()
    }

    fun applyLook() {
        // The outline alone over the picture (glass); nothing in Classic, where the picture's own edge is the box's.
        Glass.edge(this, Glass.Kind.PANEL)
        Glass.background(strip, Glass.Kind.PILL)
        for (v in buttons.values) Glass.background(v, Glass.Kind.CIRCLE)
    }

    /** The channel's [name] and what's on it ([programme], quieter after it; null: the name alone). */
    fun setInfo(name: String, programme: String?) {
        val line = if (programme.isNullOrBlank()) name else name + VodSizes.PIP_SEPARATOR + programme
        if (words.text.toString() != line) words.text = Buttons.quietTail(line, if (programme.isNullOrBlank()) -1 else name.length)
        contentDescription = line
    }

    /** Volume shows the picture's sound off ([muted]) or on. */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        buttons[PipRules.Button.VOLUME]?.apply {
            setImageResource(if (muted) R.drawable.ic_audio_off else R.drawable.ic_audio)
            contentDescription = if (muted) "Sound off" else "Sound on"
        }
    }

    /** Focus to the strip, on [PipRules.ARRIVES_ON] (Volume); the strip eases in. */
    fun takeFocus(): Boolean {
        if (visibility != VISIBLE) return false
        for (v in buttons.values) v.isFocusable = true
        return buttons[PipRules.ARRIVES_ON]?.requestFocus() == true
    }

    /** The strip goes at once (the box hides, or the picture went). */
    fun hideStrip() {
        removeCallbacks(stripCheck)
        okDown = null
        stripOn = false
        strip.animate().cancel()
        strip.alpha = 0f
        strip.translationY = rise
        strip.visibility = INVISIBLE
        for (v in buttons.values) v.isFocusable = false
    }

    private fun showStrip(on: Boolean) {
        if (stripOn == on) return
        stripOn = on
        strip.animate().cancel()
        if (on) {
            strip.visibility = VISIBLE
            if (strip.alpha == 0f) strip.translationY = rise
            strip.animate().alpha(1f).translationY(0f).setDuration(VodSizes.PIP_STRIP_MS).withLayer().start()
            onStripShown?.invoke()
            return
        }
        okDown = null
        // Hidden buttons take no focus.
        for (v in buttons.values) v.isFocusable = false
        strip.animate().alpha(0f).translationY(rise).setDuration(VodSizes.PIP_STRIP_MS).withLayer()
            .withEndAction { if (!stripOn) strip.visibility = INVISIBLE }.start()
    }

    private fun onKey(b: PipRules.Button, keyCode: Int, event: KeyEvent): Boolean {
        val arrow = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> PipRules.Arrow.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PipRules.Arrow.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> PipRules.Arrow.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PipRules.Arrow.DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // The release of a press that started here (not the OK that brought focus from elsewhere).
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) okDown = b
                if (event.action == KeyEvent.ACTION_UP) {
                    val pressed = okDown == b && !event.isCanceled
                    okDown = null
                    if (pressed) onButton?.invoke(b)
                }
                return true
            }
            else -> return false
        }
        okDown = null
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (val step = PipRules.step(b, arrow)) {
            is PipRules.Step.To -> buttons[step.button]?.requestFocus()
            PipRules.Step.Stay -> Unit
            else -> onOut?.invoke(step)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stripCheck)
        strip.animate().cancel()
        super.onDetachedFromWindow()
    }

    /** Whether [v] is one of the strip's buttons. */
    fun owns(v: View?): Boolean = v != null && v in buttons.values
}
