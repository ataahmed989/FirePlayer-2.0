package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.RowMotion
import app.fireplayer.vod.Card
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle

/**
 * One row of a Films & Shows page: its title (16 sp) and its strip of cards ([CardStrip]), moved like the Episodes
 * sheet's ([RowMotion]): the chosen card at the left edge (page x 36, screen x 94), grown, with a sliver of the one
 * before it showing (this band clips at the page's left edge). It owns its items, kept and filled in place when new
 * cards come (so focus survives), and remembers the card chosen in it ([chosen]). Keys on its items go to the page
 * ([Listener]); nothing here asks Android's focus search.
 */
@SuppressLint("ViewConstructor")
class TitleRow(context: Context, private val t: Tokens, val spec: RowSpec, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onItemFocused(row: TitleRow, index: Int)
        /** Focus left this row's items altogether. True: the chosen card shrinks back (false: the small window opened). */
        fun onRowLeft(row: TitleRow): Boolean
        fun onItemKey(row: TitleRow, index: Int, keyCode: Int, event: KeyEvent): Boolean
    }

    val style: RowStyle get() = spec.style
    val strip = CardStrip(context, t, spec.style)
    private val title = t.label(context, 16f, t.bold, TITLE_COLOR).apply { text = spec.label }

    /** The items in order: a card each, then See all when there is one; genre chips in a GENRES row. */
    val items = ArrayList<View>()
    var cards: List<Card> = emptyList()
        private set
    var genres: List<String> = emptyList()
        private set
    var hasSeeAll = false
        private set

    /** The item chosen in this row (kept while focus is elsewhere). */
    var chosen = 0

    // The page's bookkeeping for this row's data.
    /** Bumped for each load: older answers are dropped. */
    var generation = 0
    var cancel: Cancel? = null
    /** Answered at least once with something to go by (cards, or a final "none"). */
    var answered = false
    /** Cards that came while the viewer was in this row and would have moved what they're on: shown when they leave. */
    var pending: Pair<List<Card>, Boolean>? = null
    /** Whether its pictures are bound, and which cards. */
    var boundRange: IntRange = IntRange.EMPTY

    val motion = RowMotion(object : RowMotion.Host {
        override val itemCount: Int get() = items.size
        override fun itemLeft(i: Int): Float = strip.itemLeft(i)
        override fun place(offset: Float) {
            strip.translationX = -offset
        }

        override fun item(i: Int, share: Float, shift: Float) {
            val v = items[i]
            v.translationX = shift
            val grow = 1f + VodSizes.grow(style) * share
            val g = if (v is RankedItem) v.poster else v
            g.scaleX = grow
            g.scaleY = grow
            when (v) {
                is CardView -> v.focus = share
                is RankedItem -> v.poster.focus = share
            }
        }
    })

    private val stripTop = t.dp(VodSizes.stripTop())
    private val pageX = t.dp(VodSizes.PAGE_X)

    private val focusWatch = OnFocusChangeListener { v, has ->
        val i = items.indexOf(v)
        if (i < 0) return@OnFocusChangeListener
        if (has) {
            chosen = i
            motion.choose(i)
            listener.onItemFocused(this, i)
        } else {
            // To the next card: that one takes over at once. Elsewhere: the card shrinks back, the row stays put.
            post {
                if (strip.findFocus() == null && listener.onRowLeft(this)) motion.shrink()
            }
        }
    }

    private val keyWatch = OnKeyListener { v, keyCode, event ->
        val i = items.indexOf(v)
        if (i < 0) false else listener.onItemKey(this, i, keyCode, event)
    }

    init {
        motion.extra = t.dpf(VodSizes.extra(spec.style))
        // The chosen card's ring (and a rank number's edge) draw just outside the strip; the page clips at its edges.
        clipChildren = false
        clipToPadding = false
        addView(title, LayoutParams(t.dp(VodSizes.tabHeaderW()), LayoutParams.WRAP_CONTENT).apply { leftMargin = pageX })
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, t.dp(VodSizes.stripH(spec.style))).apply {
            leftMargin = pageX
            topMargin = stripTop
        })
        // Chips are laid out at their own widths: their places are known once laid out.
        if (spec.style == RowStyle.GENRES) strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> motion.apply() }
        visibility = GONE
    }

    /** The band's height: title, cards and the gap under them. */
    val bandHeight: Int get() = t.dp(VodSizes.bandH(spec.style))

    /** The index of the item holding focus, or -1. */
    fun focusedIndex(): Int {
        val f = strip.findFocus() ?: return -1
        return items.indexOf(f)
    }

    /**
     * Shows [cards] (and a See all card after them with [seeAll]), filling the items in place: the same views stay, so
     * a focused one keeps focus. The caller moves focus off items that go first.
     */
    fun bind(cards: List<Card>, seeAll: Boolean) {
        this.cards = cards
        val oldCards = items.size - if (hasSeeAll) 1 else 0
        // Cards that go (from the end), never the See all card.
        for (i in oldCards - 1 downTo cards.size) {
            val v = items.removeAt(i)
            (v as? CardView)?.releaseImage()
            (v as? RankedItem)?.poster?.releaseImage()
            strip.removeView(v)
        }
        // New cards, before the See all card.
        for (i in oldCards until cards.size) {
            val v = makeItem(seeAll = false)
            items.add(i, v)
            strip.addView(v, i)
        }
        if (seeAll && !hasSeeAll) {
            val v = makeItem(seeAll = true)
            items += v
            strip.addView(v)
        } else if (!seeAll && hasSeeAll) {
            val v = items.removeAt(items.size - 1)
            strip.removeView(v)
        }
        hasSeeAll = seeAll
        cards.forEachIndexed { i, c ->
            when (val v = items[i]) {
                is RankedItem -> {
                    v.poster.bind(c)
                    v.setRank(c.rank ?: (i + 1))
                    v.contentDescription = c.title.name
                }
                is CardView -> v.bind(c)
            }
        }
        if (seeAll) (items.last() as CardView).bindSeeAll()
        if (items.isEmpty()) chosen = 0 else chosen = chosen.coerceIn(0, items.size - 1)
        boundRange = IntRange.EMPTY
        motion.apply()
    }

    /** Shows genre chips: OK on one opens that genre's See all. */
    fun bindGenres(names: List<String>) {
        genres = names
        while (items.size > names.size) {
            val v = items.removeAt(items.size - 1)
            strip.removeView(v)
        }
        while (items.size < names.size) {
            val v = makeItem(seeAll = false)
            items += v
            strip.addView(v)
        }
        names.forEachIndexed { i, n -> (items[i] as TextView).text = n }
        chosen = if (items.isEmpty()) 0 else chosen.coerceIn(0, items.size - 1)
        motion.apply()
    }

    /** The item chosen in this row takes focus (back to this row from another, or into the page). */
    fun focusChosen(): Boolean {
        if (items.isEmpty()) return false
        chosen = chosen.coerceIn(0, items.size - 1)
        return items[chosen].requestFocus()
    }

    /** Pictures for the cards in [range]; the others let theirs go. */
    fun bindImages(range: IntRange) {
        boundRange = range
        for (i in items.indices) {
            val card = cardViewAt(i) ?: continue
            if (i in range) card.bindImage() else card.releaseImage()
        }
    }

    fun releaseImages() {
        boundRange = IntRange.EMPTY
        for (i in items.indices) cardViewAt(i)?.releaseImage()
    }

    /** The card view drawn for item [i] (a Top 10 item's poster), or null for a chip. */
    fun cardViewAt(i: Int): CardView? = when (val v = items.getOrNull(i)) {
        is RankedItem -> v.poster
        is CardView -> v
        else -> null
    }

    /** Whether item [i] is the See all card. */
    fun isSeeAll(i: Int): Boolean = hasSeeAll && i == items.size - 1

    /** Redraws what reads the look as it draws (See all cards, chips). */
    fun applyLook() {
        for (v in items) {
            if (v is TextView) v.background = chipBackground() else v.invalidate()
        }
    }

    private fun makeItem(seeAll: Boolean): View {
        val v: View = when {
            spec.style == RowStyle.GENRES -> chip()
            seeAll -> CardView(context, t, CardView.Kind.SEE_ALL)
            spec.style == RowStyle.RANKED -> RankedItem(context, t)
            spec.style == RowStyle.WIDE -> CardView(context, t, CardView.Kind.WIDE)
            else -> CardView(context, t, CardView.Kind.POSTER)
        }
        v.isFocusable = true
        // OK is read by the row's key listener (a short press, or held for the small window): never View's own click.
        v.isClickable = false
        v.isLongClickable = false
        v.onFocusChangeListener = focusWatch
        v.setOnKeyListener(keyWatch)
        return v
    }

    /** A genre chip: a flat pill (glass in a moving strip would show the wrong wallpaper), white with blue when chosen. */
    private fun chip(): TextView = t.label(context, 14f, t.semibold).apply {
        gravity = Gravity.CENTER
        val pad = t.dp(VodSizes.CHIP_PAD)
        setPadding(pad, 0, pad, 0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(t.accent, t.text)))
        background = chipBackground()
    }

    private fun chipBackground() = StateListDrawable().apply {
        val r = t.dpf(VodSizes.CHIP_H / 2f)
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = r; setColor(t.focusFill) })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = r
            if (Glass.on) {
                setColor(GLASS_FILL)
                setStroke(t.dp(1f).coerceAtLeast(1), GLASS_RIM)
            } else {
                setColor(t.rowFill)
            }
        })
    }

    private companion object {
        /** Row titles: the sizing page's light grey-blue. */
        const val TITLE_COLOR = 0xFFE3E9F0.toInt()
        const val GLASS_FILL = 0x14FFFFFF
        const val GLASS_RIM = 0x24FFFFFF
    }
}
