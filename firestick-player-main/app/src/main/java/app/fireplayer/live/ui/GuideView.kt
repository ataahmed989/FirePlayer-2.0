package app.fireplayer.live.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.LongSparseArray
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.Programme
import app.fireplayer.shell.Tokens
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The compact TV guide grid: channel numbers, logos and names on the left, programmes as duration-proportional
 * cells to the right, a time header and a now line. One custom View draws only the rows and minutes on screen, so
 * thousands of channels cost no more than ten; programmes are asked for a few screens around what is visible
 * ([Callbacks.needProgrammes]) and arrive from a background loader.
 *
 * It draws no panel of its own: the rows, cells and header sit straight on the page's wallpaper (classic: dark rounded
 * cells; glass: light tints with a rim).
 *
 * The D-pad moves a focus cell: up/down keep the same time column, left/right step programme by programme, and the
 * time window scrolls in half-hour steps. The white highlight glides from cell to cell, in step with the rows and the
 * time window sliding (one animator drives all three, so they never drift apart). Left from the programme on air
 * leaves the grid ([Callbacks.onLeaveLeft]), which shows the playlist/group column again. Nothing here starts
 * playback: [Callbacks.onPick] (Center) decides.
 */
class GuideView(context: Context, private val t: Tokens) : View(context) {

    interface Callbacks {
        fun onFocusMoved(channel: Channel, cell: Cell?)
        fun onPick(channel: Channel, cell: Cell?)
        fun onLeaveLeft()
        fun needProgrammes(channels: List<Channel>, from: Long, to: Long)
        fun logo(url: String): Bitmap?
    }

    var callbacks: Callbacks? = null
    var clock: () -> Long = System::currentTimeMillis

    /** The look: glass draws cells as light tints with a rim; classic as dark rounded cells. Same geometry either way. */
    var glass = false
        set(v) { field = v; invalidate() }

    /** The channel playing (in the miniature or full screen): its name and the ▶ after it are blue. */
    var playingKey: ChannelKey? = null
        set(v) { field = v; invalidate() }

    /** Playlists whose guide is still being loaded for the first time: their empty rows say so. */
    var loadingPlaylists: Set<String> = emptySet()
        set(v) { if (field != v) { field = v; for (r in rows.values) r.cells.forEach { it.cachedText = null }; invalidate() } }

    private fun noInfo(ch: Channel) = if (ch.playlistId in loadingPlaylists) "Loading the guide…" else "No information"

    // ---- Geometry (dp, from the user's concepts: 29 dp rows, about 2.5 hours across the full width) -----------------
    private val headerH = t.dpf(26f)
    private val rowH = t.dpf(29f)
    /** Between cells, both ways: cells are 26 dp tall. */
    private val gap = t.dpf(3f)
    private val radiusClassic = t.dpf(6f)
    private val radiusGlass = t.dpf(7f)
    private val hairline = t.dpf(0.5f)
    private val numberLead = t.dpf(4f)
    private val numberMin = t.dpf(20f)
    /** Where channel numbers end (right-aligned): wider than [numberMin] for long provider numbers (29342). */
    private var numberRight = numberMin
    private val logoGap = t.dpf(8f)
    private val logoW = t.dpf(46f)
    private val logoH = t.dpf(26f)
    private val nameGap = t.dpf(8f)
    private val nameEnd = t.dpf(4f)
    private val markerGap = t.dpf(6f)
    private val markerW = t.dpf(8f)
    /**
     * The channel column. Wider than the concepts' ~236 dp because Figtree is wider than their lettering: at 256 dp a
     * name as long as "USA Cinemax Action West" still fits beside a two-digit number and its logo.
     */
    private val channelW = t.dpf(256f)
    private val pxPerMin = t.dpf(4.6f)
    private val textInset = t.dpf(8f)

    /** The channel column's width in pixels: the programme cells start here (for aligning things beside the guide). */
    val channelColumnWidth: Int get() = channelW.roundToInt()

    /**
     * The height in pixels that shows the time header and exactly [rows] channel rows (29 dp each): sized to it, the
     * guide never shows a cut row at the bottom. Any other height works too; the rows below the last whole one are
     * not drawn at rest.
     */
    fun heightFor(rows: Int): Int = ceil(headerH + rows.coerceAtLeast(1) * rowH).toInt()

    // ---- State ----------------------------------------------------------------------------------------------------
    private var channels: List<Channel> = emptyList()
    /** Per row of [channels]: the key, the number as text, and the name as last ellipsized (drawing allocates nothing). */
    private var keys: Array<ChannelKey> = emptyArray()
    private var numbers: Array<String> = emptyArray()
    private var names: Array<CharSequence?> = emptyArray()
    private var nameWidths = FloatArray(0)
    private var namePlaying = BooleanArray(0)
    private val rows = HashMap<ChannelKey, RowData>()
    private var focusRow = 0
    private var cursor = 0L
    private var windowStart = 0L
    private var scrollY = 0f
    /** Where the rows are scrolling to (equal to [scrollY] at rest). */
    private var scrollTarget = 0f
    /** The time window drawn this many pixels to the right of [windowStart] while it slides; 0 at rest. */
    private var windowX = 0f
    private var requested: String? = null

    private class RowData(val from: Long, val to: Long, val cells: List<Cell>)

    // ---- Motion ---------------------------------------------------------------------------------------------------
    /** The focus highlight between cells (content coordinates: x as at rest, y = screen y + [scrollY]). */
    private val glide = FocusGlide()
    /** The cell the glide left, so its title turns back from blue as the highlight leaves it. */
    private var glideFromRow = -1
    private var glideFromCell: Cell? = null
    private var motionFromY = 0f
    private var motionFromX = 0f
    private val ease = DecelerateInterpolator(1.5f)
    private val quickEase = DecelerateInterpolator(1f)
    /** One animator for every move: rows scrolling, the time window sliding and the highlight gliding, in step. */
    private val motion: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { a ->
            val f = a.animatedFraction
            scrollY = motionFromY + (scrollTarget - motionFromY) * f
            windowX = motionFromX * (1f - f)
            glide.fraction = f
            invalidate()
        }
    }

    // ---- Paints ---------------------------------------------------------------------------------------------------
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(14f) * fontScale() }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(14f) * fontScale() }
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(13f) * fontScale(); color = t.text; textAlign = Paint.Align.RIGHT }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(13f) * fontScale(); color = t.text }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.semibold; textSize = t.dpf(13f) * fontScale(); color = t.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t.dpf(1f) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = t.dpf(2f) }
    private val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val marker = Path()
    private val rect = RectF()
    private val focusRect = RectF()
    private val highlight = RectF()
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")

    // Header labels, made once per minute / per half hour rather than every frame.
    private var dateText = ""
    private var dateMinute = Long.MIN_VALUE
    private val slotLabels = LongSparseArray<String>()

    // Set while drawing the rows: where the focused (and the glide's previous) title went, for the blue copy on top.
    private var drawWs = 0L
    private var rowsBottom = 0f
    private var overTo: CharSequence? = null
    private var overToX = 0f
    private var overToY = 0f
    private var overFrom: CharSequence? = null
    private var overFromX = 0f
    private var overFromY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val now = System.currentTimeMillis()
        windowStart = GuideCells.slotStart(now)
        cursor = now
    }

    private fun fontScale() = resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

    /** The rows' per-channel text, made again for a new list: numbers, and names ellipsized lazily as they show. */
    private fun indexChannels() {
        val n = channels.size
        keys = Array(n) { channels[it].key }
        numbers = Array(n) { channels[it].number.toString() }
        names = arrayOfNulls(n)
        nameWidths = FloatArray(n)
        namePlaying = BooleanArray(n)
        // Five-digit provider numbers (29342) are wider than the usual column: it grows, the logo and name move over.
        val digits = numbers.maxOfOrNull { it.length } ?: 1
        numberRight = maxOf(numberMin, numberLead + numberPaint.measureText("8".repeat(digits)))
    }

    // ---- Data -----------------------------------------------------------------------------------------------------

    /** Shows [list]; focus goes to [focus] (or stays on the same channel, or the first). */
    fun setChannels(list: List<Channel>, focus: ChannelKey?) {
        stopMotion()
        val keep = focus ?: channels.getOrNull(focusRow)?.key
        channels = list
        indexChannels()
        focusRow = list.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        // The time window catches up with now (it may have been opened a while ago), unless the viewer had moved
        // ahead into later programmes.
        val now = clock()
        if (cursor < now) cursor = now
        if (windowStart < GuideCells.slotStart(now)) windowStart = GuideCells.slotStart(now)
        jumpScroll(targetScroll(focusRow) * rowH)
        requested = null
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** Programmes arrived for [from, to). Rows not in the map have no listings in that range. */
    fun putProgrammes(channelsLoaded: List<Channel>, map: Map<ChannelKey, List<Programme>>, from: Long, to: Long) {
        for (c in channelsLoaded) rows[c.key] = RowData(from, to, GuideCells.cells(map[c.key].orEmpty(), from, to))
        // Bounded: rows far from the focus are dropped (they load again if scrolled back to).
        if (rows.size > MAX_ROWS) {
            val keep = channels.subList((focusRow - MAX_ROWS / 3).coerceAtLeast(0), (focusRow + MAX_ROWS / 3).coerceAtMost(channels.size)).map { it.key }.toHashSet()
            rows.keys.retainAll(keep)
        }
        // A cursor on a gap that's now a programme: the details follow.
        notifyFocus()
        invalidate()
    }

    /** Forgets every listing (new EPG): rows load again as they show. */
    fun clearProgrammes() {
        rows.clear()
        requested = null
        ensureLoaded()
        invalidate()
    }

    fun focusedChannel(): Channel? = channels.getOrNull(focusRow)

    fun focusedCell(): Cell? = focusedChannel()?.let { cellsOf(it) }?.let { cells -> cells.getOrNull(GuideCells.at(cells, cursor)) }

    fun channelList(): List<Channel> = channels

    /** The loaded programmes of [ch], in order (empty when not loaded or none listed). */
    fun programmesOf(ch: Channel): List<Programme> = rows[ch.key]?.cells?.mapNotNull { it.programme }.orEmpty()

    /** The same scope re-read (a favorite changed): focus stays on its channel, or the nearest row. */
    fun updateChannels(list: List<Channel>) {
        stopMotion()
        val key = focusedChannel()?.key
        channels = list
        indexChannels()
        val i = list.indexOfFirst { it.key == key }
        focusRow = if (i >= 0) i else focusRow.coerceAtMost((list.size - 1).coerceAtLeast(0))
        jumpScroll(targetScroll(focusRow) * rowH)
        notifyFocus()
        invalidate()
    }

    /** Moves focus to [key] (after a channel change in full screen, the guide shows where you are). */
    fun focusChannel(key: ChannelKey) {
        val i = channels.indexOfFirst { it.key == key }
        if (i < 0) return
        val now = clock()
        cursor = now
        windowStart = GuideCells.slotStart(now)
        moveTo(i, 0L)
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) {
            // Nothing listed here (an empty group, no favorites yet): Left goes back to the groups column.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { callbacks?.onLeaveLeft(); return true }
            return super.onKeyDown(keyCode, event)
        }
        // A held key repeats every ~50 ms: each move is a short glide, so the highlight keeps up without jumping.
        val d = if (event.repeatCount > 0) REPEAT_MS else MOVE_MS
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (focusRow > 0) moveTo(focusRow - 1, d) else return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (focusRow < channels.size - 1) moveTo(focusRow + 1, d) }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveTo((focusRow - visibleRows()).coerceAtLeast(0), d)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveTo((focusRow + visibleRows()).coerceAtMost(channels.size - 1), d)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(+1, d)
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, d)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) focusedChannel()?.let { callbacks?.onPick(it, focusedCell()) }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun step(dir: Int, duration: Long) {
        val ch = focusedChannel() ?: return
        val cells = cellsOf(ch) ?: return
        val i = GuideCells.at(cells, cursor)
        val now = clock()
        val next: Long
        if (dir < 0) {
            val cell = cells.getOrNull(i)
            // From what's on now (or earlier), Left leaves the grid for the groups column.
            if (cell == null || cell.start <= now) {
                callbacks?.onLeaveLeft()
                return
            }
            if (i == 0) {
                // A later stretch is loaded but not back to now: load it rather than leave the grid.
                requested = null
                ensureLoaded(force = true)
                return
            }
            next = GuideCells.cursorFor(cells[i - 1], now)
        } else {
            val cell = cells.getOrNull(i + 1)
            if (cell == null) {
                // The end of what's loaded: ask for the hours after it; the next Right carries on.
                requested = null
                ensureLoaded(force = true, until = (rows[ch.key]?.to ?: windowStart) + windowMs() + 4 * 60 * 60_000L)
                return
            }
            next = GuideCells.cursorFor(cell, now)
        }
        beginGlide()
        cursor = next
        var fromX = windowX
        val cell = focusedCell()
        if (cell != null) {
            val target = GuideCells.windowFor(cell, windowStart, windowMs(), GuideCells.slotStart(now))
            if (target != windowStart) {
                // Drawn from where it is (shifted right by the step), sliding to the new start; the glide's start
                // moves with the content it was drawn over.
                val shift = (target - windowStart) / 60_000f * pxPerMin
                windowStart = target
                fromX += shift
                glide.shiftX(-shift)
            }
        }
        startMotion(scrollTarget, fromX, duration)
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** Focus to [row]; [duration] 0 jumps there (no scroll, no glide). */
    private fun moveTo(row: Int, duration: Long) {
        val animate = duration > 0L && height > 0
        if (animate) beginGlide()
        focusRow = row
        startMotion(targetScroll(row) * rowH, windowX, if (animate) duration else 0L)
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** First visible row that keeps the focus row one row inside the edges where it can. */
    private fun targetScroll(row: Int): Int {
        val visible = visibleRows()
        val top = (scrollTarget / rowH).roundToInt()
        val maxTop = (channels.size - visible).coerceAtLeast(0)
        return when {
            row < top + 1 -> row - 1
            row > top + visible - 2 -> row - visible + 2
            else -> top
        }.coerceIn(0, maxTop)
    }

    /** The highlight starts gliding from where it's drawn now (called before the focus changes). */
    private fun beginGlide() {
        glideFromRow = -1
        glideFromCell = null
        if (!isFocused || !focusTarget(focusRect)) { glide.clear(); return }
        glide.update(focusRect.left, focusRect.top, focusRect.right, focusRect.bottom)
        glide.begin(glide.left, glide.top, glide.right, glide.bottom)
        glideFromRow = focusRow
        glideFromCell = focusedCell()
    }

    /**
     * Scrolls the rows to [toScroll] and slides the time window from [fromWindowX] to rest, the highlight gliding
     * with them; [duration] 0 puts everything in place at once.
     */
    private fun startMotion(toScroll: Float, fromWindowX: Float, duration: Long) {
        motion.cancel()
        scrollTarget = toScroll
        if (duration <= 0L || height == 0) {
            scrollY = toScroll
            windowX = 0f
            glide.clear()
            invalidate()
            return
        }
        motionFromY = scrollY
        motionFromX = fromWindowX
        windowX = fromWindowX
        motion.duration = duration
        motion.interpolator = if (duration < MOVE_MS) quickEase else ease
        motion.start()
    }

    /** Everything to rest where it was going. */
    private fun stopMotion() {
        motion.cancel()
        scrollY = scrollTarget
        windowX = 0f
        glide.clear()
    }

    private fun jumpScroll(y: Float) {
        scrollY = y
        scrollTarget = y
    }

    private fun visibleRows(): Int = ((height - headerH) / rowH).toInt().coerceAtLeast(1)

    private fun windowMs(): Long = (((width - channelW) / pxPerMin) * 60_000).toLong().coerceAtLeast(GuideCells.SLOT_MS)

    private fun cellsOf(ch: Channel): List<Cell>? = rows[ch.key]?.cells

    /** The focused cell's rect at rest, in content coordinates; false when it has none (row not loaded, off screen). */
    private fun focusTarget(out: RectF): Boolean {
        if (focusRow !in keys.indices) return false
        val cells = rows[keys[focusRow]]?.cells ?: return false
        val cell = cells.getOrNull(GuideCells.at(cells, cursor)) ?: return false
        val x0 = GuideCells.cellLeft(cell.start, windowStart, channelW, pxPerMin, gap)
        val x1 = GuideCells.cellRight(cell.stop, windowStart, channelW, width.toFloat(), pxPerMin, gap)
        if (x1 - x0 < 1f) return false
        val top = headerH + focusRow * rowH
        out.set(x0, top + gap / 2, x1, top + rowH - gap / 2)
        return true
    }

    private fun notifyFocus() {
        val ch = focusedChannel() ?: return
        val cell = focusedCell()
        callbacks?.onFocusMoved(ch, cell)
        val p = cell?.programme
        contentDescription = buildString {
            append(ch.number).append(' ').append(ch.name).append(". ")
            if (p != null) append(p.title).append(", ").append(timeFormat.format(Date(p.start))).append(" to ").append(timeFormat.format(Date(p.stop)))
            else append(noInfo(ch))
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true && isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /**
     * Asks for listings around what's visible: two screens of rows each way, from before now to the window plus a
     * few hours ([until] reaches further). Every loaded row covers now, so its first cell is never in the future.
     */
    private fun ensureLoaded(force: Boolean = false, until: Long = 0L) {
        if (channels.isEmpty() || height == 0) return
        val visible = visibleRows()
        // Where the rows are going, not where a scroll in progress has got to.
        val top = (scrollTarget / rowH).toInt().coerceIn(0, channels.size)
        val from = (top - visible * 2).coerceAtLeast(0)
        val to = (top + visible * 3).coerceAtMost(channels.size)
        if (from >= to) return
        val base = minOf(windowStart, GuideCells.slotStart(clock()))
        val tFrom = base - GuideCells.SLOT_MS * 2
        val tTo = maxOf(windowStart + windowMs() + 4 * 60 * 60_000L, until)
        val missing = channels.subList(from, to).filter { c ->
            rows[c.key].let { it == null || it.from > base || it.to < windowStart + windowMs() || (force && it.to < tTo) }
        }
        if (missing.isEmpty()) return
        val key = "${missing.first().key}|${missing.size}|$tFrom"
        if (key == requested) return
        requested = key
        callbacks?.needProgrammes(missing, tFrom, tTo)
    }

    /** After the width stops changing: listings for the wider window, if any are missing (usually none). */
    private val widthSettled = Runnable { ensureLoaded() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && h == oldh) {
            // Only the width changed (the groups column opened or closed). The rows, the scroll and glide in progress
            // and the request de-duplication stay as they are (cells are worked out from the width as they're
            // drawn); listings are checked once, after it settles.
            removeCallbacks(widthSettled)
            postDelayed(widthSettled, WIDTH_SETTLE_MS)
            return
        }
        stopMotion()
        jumpScroll(targetScroll(focusRow) * rowH)
        requested = null
        ensureLoaded()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // The highlight shows (or goes) on its cell at once; only moves within the grid glide.
        glide.clear()
        if (gainFocus) {
            // Back in the grid: what's on now, on the same channel.
            val now = clock()
            if (cursor < now) cursor = now
            notifyFocus()
        }
        invalidate()
    }

    // ---- Drawing --------------------------------------------------------------------------------------------------

    private fun xOf(time: Long) = GuideCells.xOf(time, drawWs, channelW, pxPerMin)

    private fun cellRadius() = if (glass) radiusGlass else radiusClassic

    override fun onDraw(canvas: Canvas) {
        val now = clock()
        val w = width.toFloat()
        drawWs = windowStart - (windowX / pxPerMin * 60_000).toLong()
        val wEnd = drawWs + ((w - channelW) / pxPerMin * 60_000).toLong()
        // Whole rows only at rest (a row cut at the bottom shows only while the rows slide).
        rowsBottom = minOf(height.toFloat(), headerH + visibleRows() * rowH)

        drawHeader(canvas, now, wEnd)

        overTo = null
        overFrom = null
        val rowsEnd = minOf(rowsBottom, headerH + channels.size * rowH - scrollY)
        canvas.save()
        canvas.clipRect(0f, headerH, w, rowsBottom)
        if (channels.isNotEmpty()) {
            val firstRow = (scrollY / rowH).toInt().coerceAtLeast(0)
            val lastRow = ((scrollY + rowsBottom - headerH) / rowH).toInt().coerceAtMost(channels.size - 1)
            for (r in firstRow..lastRow) {
                val top = headerH + r * rowH - scrollY
                drawChannel(canvas, r, top)
                canvas.save()
                canvas.clipRect(channelW, top, w, top + rowH)
                drawCells(canvas, r, top, now, wEnd)
                canvas.restore()
            }
            if (isFocused) drawFocus(canvas)
        }
        // The now line over the cells and the highlight, down to the last row.
        val nx = xOf(now)
        val showNow = nx >= channelW && nx <= w && rowsEnd > headerH
        if (showNow) canvas.drawLine(nx, headerH, nx, rowsEnd, nowPaint)
        canvas.restore()
        // The dot sits centred on the header's rule (the horizontal time line), the now line running down from it.
        if (showNow) canvas.drawCircle(nx, headerH - t.dpf(0.5f), t.dpf(3.5f), nowPaint)
    }

    private fun drawHeader(canvas: Canvas, now: Long, wEnd: Long) {
        val base = headerH - t.dpf(9f)
        canvas.drawText(dateLabel(now), numberLead, base, datePaint)
        var slot = GuideCells.slotStart(drawWs)
        canvas.save()
        // Labels slide with the time window and pass under the channel column's edge rather than popping.
        canvas.clipRect(channelW, 0f, width.toFloat(), headerH)
        while (slot < wEnd) {
            canvas.drawText(slotLabel(slot), xOf(slot) + t.dpf(4f), base, headerPaint)
            slot += GuideCells.SLOT_MS
        }
        canvas.restore()
        fill.color = if (glass) GLASS_RULE else CLASSIC_RULE
        canvas.drawRect(0f, headerH - t.dpf(1f), width.toFloat(), headerH, fill)
    }

    /** "Tue, Nov 17 · 5:28 PM", made again when the minute changes. */
    private fun dateLabel(now: Long): String {
        val minute = now / 60_000
        if (minute != dateMinute) {
            dateMinute = minute
            dateText = DateFormat.format(datePattern, now).toString() + " · " + timeFormat.format(Date(now))
        }
        return dateText
    }

    private fun slotLabel(slot: Long): String {
        slotLabels.get(slot)?.let { return it }
        if (slotLabels.size() > 64) slotLabels.clear()
        return timeFormat.format(Date(slot)).also { slotLabels.put(slot, it) }
    }

    private fun drawChannel(canvas: Canvas, r: Int, top: Float) {
        val ch = channels[r]
        val playing = keys[r] == playingKey
        val cy = top + rowH / 2
        // A hairline between channel rows; no fill behind them.
        fill.color = if (glass) GLASS_DIVIDER else CLASSIC_DIVIDER
        canvas.drawRect(0f, top + rowH - hairline, channelW - gap / 2, top + rowH, fill)

        canvas.drawText(numbers[r], numberRight, baseline(cy, numberPaint), numberPaint)

        val lx = numberRight + logoGap
        val bmp = ch.logo?.let { callbacks?.logo(it) }
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val s = minOf(logoW / bmp.width, logoH / bmp.height)
            val bw = bmp.width * s
            val bh = bmp.height * s
            rect.set(lx + (logoW - bw) / 2, cy - bh / 2, lx + (logoW + bw) / 2, cy + bh / 2)
            canvas.drawBitmap(bmp, null, rect, logoPaint)
        } else {
            fill.color = LOGO_EMPTY
            rect.set(lx, cy - logoH / 2, lx + logoW, cy + logoH / 2)
            canvas.drawRoundRect(rect, t.dpf(4f), t.dpf(4f), fill)
        }

        val nx = lx + logoW + nameGap
        namePaint.color = if (playing) t.accent else t.text
        val name = nameOf(r, nx, playing)
        canvas.drawText(name, 0, name.length, nx, baseline(cy, namePaint), namePaint)
        if (playing) {
            // The channel playing: a small blue ▶ just after its name.
            val mx = nx + nameWidths[r] + markerGap
            val s = t.dpf(4.5f)
            marker.reset()
            marker.moveTo(mx, cy - s); marker.lineTo(mx + s * 1.6f, cy); marker.lineTo(mx, cy + s); marker.close()
            fill.color = t.accent
            canvas.drawPath(marker, fill)
        }
    }

    /** Row [r]'s name ellipsized to the column (less room when the ▶ follows it), kept until the list changes. */
    private fun nameOf(r: Int, nx: Float, playing: Boolean): CharSequence {
        names[r]?.let { if (namePlaying[r] == playing) return it }
        val avail = channelW - nameEnd - nx - if (playing) markerGap + markerW else 0f
        val s = TextUtils.ellipsize(channels[r].name, namePaint, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END)
        names[r] = s
        namePlaying[r] = playing
        nameWidths[r] = namePaint.measureText(s, 0, s.length)
        return s
    }

    private fun drawCells(canvas: Canvas, r: Int, top: Float, now: Long, wEnd: Long) {
        val w = width.toFloat()
        val cy = top + rowH / 2
        val radius = cellRadius()
        val row = rows[keys[r]]
        if (row == null) {
            // Not loaded yet: one faint bar, no text.
            fill.color = if (glass) GLASS_EMPTY else CLASSIC_EMPTY
            rect.set(channelW + gap / 2, top + gap / 2, w - gap / 2, top + rowH - gap / 2)
            canvas.drawRoundRect(rect, radius, radius, fill)
            return
        }
        val ch = channels[r]
        val cells = row.cells
        val focusIndex = if (r == focusRow) GuideCells.at(cells, cursor) else -1
        val gridFocused = isFocused
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.stop <= drawWs || cell.start >= wEnd) continue
            val x0 = GuideCells.cellLeft(cell.start, drawWs, channelW, pxPerMin, gap)
            val x1 = GuideCells.cellRight(cell.stop, drawWs, channelW, w, pxPerMin, gap)
            if (x1 - x0 < 1f) continue
            rect.set(x0, top + gap / 2, x1, top + rowH - gap / 2)
            val isFocus = i == focusIndex
            // The focus cell while the column has the focus: a shade lighter, its title semibold (no white).
            val selected = isFocus && !gridFocused
            fill.color = when {
                glass && selected -> GLASS_SELECTED
                glass -> GLASS_CELL
                selected -> CLASSIC_SELECTED
                else -> CLASSIC_CELL
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (glass) {
                val h = rim.strokeWidth / 2
                rect.inset(h, h)
                rim.color = GLASS_RIM
                canvas.drawRoundRect(rect, radius - h, radius - h, rim)
            }
            val avail = (x1 - x0 - 2 * textInset).toInt()
            if (avail > t.dpf(12f)) {
                textPaint.typeface = if (isFocus) t.semibold else t.regular
                textPaint.color = if (cell.programme == null || cell.stop <= now) TEXT_DIM else t.text
                val text = titleOf(cell, ch, avail, isFocus)
                val tx = x0 + textInset
                val ty = baseline(cy, textPaint)
                canvas.drawText(text, 0, text.length, tx, ty, textPaint)
                // Under the white highlight these are drawn again in blue (drawFocus).
                if (isFocus && gridFocused) { overTo = text; overToX = tx; overToY = ty }
                if (!isFocus && r == glideFromRow && cell === glideFromCell) { overFrom = text; overFromX = tx; overFromY = ty }
            }
        }
    }

    private fun titleOf(cell: Cell, ch: Channel, avail: Int, bold: Boolean): CharSequence {
        val cached = cell.cachedText
        if (cached != null && cell.cachedWidth == avail && cell.cachedBold == bold) return cached
        val title = cell.programme?.title?.takeIf { it.isNotEmpty() } ?: if (cell.programme == null) noInfo(ch) else "Untitled"
        return TextUtils.ellipsize(title, textPaint, avail.toFloat(), TextUtils.TruncateAt.END).also {
            cell.cachedText = it
            cell.cachedWidth = avail
            cell.cachedBold = bold
        }
    }

    /**
     * The white highlight, gliding from the cell it left to the focused one; the titles under it are drawn again in
     * blue, clipped to it, so the text colours follow the highlight as it moves.
     */
    private fun drawFocus(canvas: Canvas) {
        if (!focusTarget(focusRect)) return
        glide.update(focusRect.left, focusRect.top, focusRect.right, focusRect.bottom)
        // Content to screen; kept within the cells' area so its rounded ends show while the time window slides.
        highlight.set(
            maxOf(glide.left + windowX, channelW + gap / 2), glide.top - scrollY,
            minOf(glide.right + windowX, width - gap / 2), glide.bottom - scrollY,
        )
        if (highlight.width() < 1f) return
        val radius = cellRadius()
        canvas.save()
        canvas.clipRect(channelW, headerH, width.toFloat(), rowsBottom)
        fill.color = t.focusFill
        canvas.drawRoundRect(highlight, radius, radius, fill)
        canvas.clipRect(highlight)
        textPaint.color = t.accent
        val from = overFrom
        if (from != null && glide.moving) {
            textPaint.typeface = t.regular
            canvas.drawText(from, 0, from.length, overFromX, overFromY, textPaint)
        }
        val to = overTo
        if (to != null) {
            textPaint.typeface = t.semibold
            canvas.drawText(to, 0, to.length, overToX, overToY, textPaint)
        }
        canvas.restore()
    }

    private fun baseline(cy: Float, p: Paint): Float = cy - (p.descent() + p.ascent()) / 2

    // ---- Time -----------------------------------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            val now = clock()
            // The programme on air ended under the focus: focus moves on with it.
            if (cursor < now && focusedCell()?.contains(now) == false && focusedCell()?.stop?.let { it <= now } == true) {
                cursor = now
                notifyFocus()
            }
            if (GuideCells.slotStart(now) > windowStart) {
                windowStart = GuideCells.slotStart(now)
                if (cursor < now) cursor = now
                glide.clear()
                requested = null
                ensureLoaded()
                notifyFocus()
            }
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, 30_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(widthSettled)
        stopMotion()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_ROWS = 600
        /** A move: rows scrolling, the window sliding and the highlight gliding, decelerating. */
        const val MOVE_MS = 150L
        /** A move while a key is held (repeats): short, so the highlight keeps up with the focus. */
        const val REPEAT_MS = 80L
        /** The width is taken as settled once it hasn't changed for this long. */
        const val WIDTH_SETTLE_MS = 120L

        // Classic: dark rounded cells a little lighter than the wallpaper.
        const val CLASSIC_CELL = 0xFF252A31.toInt()
        const val CLASSIC_SELECTED = 0xFF30363F.toInt()
        const val CLASSIC_EMPTY = 0x99252A31.toInt()
        const val CLASSIC_DIVIDER = 0x14FFFFFF
        const val CLASSIC_RULE = 0x2EFFFFFF
        // Glass: light tints with a lighter rim.
        const val GLASS_CELL = 0x17FFFFFF
        const val GLASS_SELECTED = 0x2EFFFFFF
        const val GLASS_EMPTY = 0x0DFFFFFF
        const val GLASS_RIM = 0x33FFFFFF
        const val GLASS_DIVIDER = 0x17FFFFFF
        const val GLASS_RULE = 0x38FFFFFF
        const val LOGO_EMPTY = 0x1FFFFFFF
        /** Past programmes and gaps ("No information"). */
        const val TEXT_DIM = 0x99F4F2EE.toInt()
    }
}
