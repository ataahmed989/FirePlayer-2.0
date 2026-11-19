package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.PageRow

/**
 * The Search tab's top: the bar, the Settings tab's row ([PageRow]: white with dark words while focused) holding the
 * magnifier and the words typed (or the hint), and beside it the round trash button (the title page's round buttons,
 * [Buttons.RoundButton]). OK on the bar opens the TV's keyboard; [onQuery] gets the words [VodSizes.SEARCH_WAIT_MS]
 * after the last change, [onSubmit] at once on the keyboard's Done. The D-pad's arrows go to [onArrow] (the page moves
 * focus itself; the words' cursor never moves).
 */
@SuppressLint("ViewConstructor")
class SearchBar(context: Context, private val t: Tokens) : LinearLayout(context) {

    /** The words once typing has paused. */
    var onQuery: ((String) -> Unit)? = null
    /** Done on the keyboard: the words, searched now (and kept in the history). */
    var onSubmit: ((String) -> Unit)? = null
    /** An arrow pressed on the bar or the trash button (key down; [fromTrash] which). */
    var onArrow: ((keyCode: Int, fromTrash: Boolean) -> Unit)? = null
    var onTrash: (() -> Unit)? = null
    /** Focus came to the bar or the trash button. */
    var onFocused: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    val input = EditText(context)
    val bar = PageRow(context, R.drawable.ic_search_rail, input)
    val trash = Buttons.RoundButton(context, t, VodSizes.SEARCH_TRASH, VodSizes.SEARCH_TRASH_ICON).apply {
        set(R.drawable.ic_trash, "Clear history")
    }
    /** The words now. */
    val text: String get() = input.text?.toString().orEmpty()
    /** Words put in by the page (a history row picked, or cleared): no wait, no search from them. */
    private var quiet = false
    private var okDown = false

    private val settle = Runnable { onQuery?.invoke(text) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        // The row shows the words' focus (no child copies the row's state back: the two together loop).
        bar.isFocusable = false
        bar.isClickable = false
        bar.setAddStatesFromChildren(true)
        input.apply {
            background = null
            setPadding(0, 0, 0, 0)
            ellipsize = null
            hint = HINT
            // The row's words' colours (light, dark on the white focused row), fainter.
            val words = ContextCompat.getColorStateList(context, R.color.sel_text_row)
            val focused = words?.getColorForState(intArrayOf(android.R.attr.state_focused), t.text) ?: t.text
            setHintTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(
                        ColorUtils.setAlphaComponent(focused, VodSizes.SEARCH_HINT_ALPHA),
                        ColorUtils.setAlphaComponent(words?.defaultColor ?: t.text, VodSizes.SEARCH_HINT_ALPHA),
                    ),
                ),
            )
            inputType = InputType.TYPE_CLASS_TEXT
            // The keyboard leaves the page in view (the results come in behind it as typing pauses).
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            // The keyboard opens on OK only, not whenever focus passes through.
            showSoftInputOnFocus = false
            setOnFocusChangeListener { _, has -> if (has) onFocused?.invoke() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(settle)
                    if (!quiet) main.postDelayed(settle, VodSizes.SEARCH_WAIT_MS)
                }
            })
            // Done (or any action the keyboard offers, or Enter): the results now, and the keyboard goes.
            setOnEditorActionListener { _, _, _ ->
                hideKeyboard()
                main.removeCallbacks(settle)
                onSubmit?.invoke(this@SearchBar.text)
                true
            }
            setOnKeyListener { _, keyCode, event -> onKey(keyCode, event, fromTrash = false) }
        }
        addView(bar, LayoutParams(t.dp(VodSizes.searchBarW()), t.dp(VodSizes.SEARCH_BAR_H)))
        // Taller than the bar: its name hangs under it (drawn over the gap, never moving the bar).
        addView(trash, LayoutParams(LayoutParams.WRAP_CONTENT, t.dp(VodSizes.SEARCH_TRASH + VodSizes.SEARCH_TRASH_LABEL)).apply {
            leftMargin = t.dp(VodSizes.SEARCH_TRASH_GAP)
            gravity = Gravity.TOP
        })
        Tokens.focusEffect(trash.button, Tokens.GROW_CIRCLE, trash.label) { has -> if (has) onFocused?.invoke() }
        trash.button.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event, fromTrash = true) }
    }

    private fun onKey(keyCode: Int, event: KeyEvent, fromTrash: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            // The release of a press that started here (not the OK that brought focus here from a history row).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) okDown = true
            if (event.action == KeyEvent.ACTION_UP && okDown && !event.isCanceled) {
                okDown = false
                if (fromTrash) onTrash?.invoke() else showKeyboard()
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            okDown = false
            if (event.action == KeyEvent.ACTION_DOWN) onArrow?.invoke(keyCode, fromTrash)
            true
        }
        else -> false
    }

    /** Focus to the bar (the keyboard stays shut until OK). */
    fun focusBar(): Boolean {
        okDown = false
        return input.requestFocus()
    }

    fun focusTrash(): Boolean = trash.button.requestFocus()

    /** Whether the bar or the trash button has focus. */
    fun hasFocusHere(): Boolean = input.isFocused || trash.button.isFocused

    /** Puts [words] in the bar without the wait (and no search from them), the cursor at their end. */
    fun setText(words: String) {
        main.removeCallbacks(settle)
        if (words == text) return
        quiet = true
        input.setText(words)
        input.setSelection(input.length())
        quiet = false
    }

    fun showKeyboard() {
        input.requestFocus()
        imm()?.showSoftInput(input, 0)
    }

    fun hideKeyboard() {
        imm()?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** No late answer for words typed before (the page hid or went). */
    fun cancel() {
        main.removeCallbacks(settle)
        hideKeyboard()
    }

    fun applyLook() {
        bar.applyLook()
        trash.applyLook()
    }

    /** Whether [v] is the bar's or the trash button's focusable. */
    fun owns(v: View?): Boolean = v === input || v === trash.button

    private fun imm() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    companion object {
        const val HINT = "Search films and shows"
    }
}
