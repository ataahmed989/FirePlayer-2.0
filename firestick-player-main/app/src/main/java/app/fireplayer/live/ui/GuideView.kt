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
import kotlin.math.roundToInt

/**
 * The compact TV guide grid: channel numbers, logos and names on the left, programmes as duration-proportional
 * cells to the right, a time header and a now line. One custom View draws only the rows and minutes on screen, so
 * thousands of channels cost no more than ten; programmes are asked for a few screens around what is visible
 * ([Callbacks.needProgrammes]) and arrive from a background loader.
 *
 * The D-pad moves a focus cell: up/down keep the same time column, left/right step programme by programme, and the
 * time window scrolls in half-hour steps. Left from the programme on air leaves the grid ([Callbacks.onLeaveLeft]),
 * which shows the playlist/group column again. Nothing here starts playback: [Callbacks.onPick] (Center) decides.
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

    /** The look: glass draws cells as light tints; classic as the original dark rows. Same geometry either way. */
    var glass = false
        set(v) { field = v; invalidate() }

    /** The channel playing (in the miniature or full screen): its name and marker stay blue. */
    var playingKey: ChannelKey? = null
        set(v) { field = v; invalidate() }

    // ---- Geometry (dp from the density reference: ~10–12 rows with the preview above) -----------------------------
    private val headerH = t.dpf(28f)
    private val rowH = t.dpf(32f)
    private val gap = t.dpf(2f)
    private val radius = t.dpf(5f)
    private val numberW = t.dpf(34f)
    private val logoW = t.dpf(38f)
    private val logoH = t.dpf(22f)
    private val channelW = t.dpf(196f)
    private val pxPerMin = t.dpf(5.4f)
    private val pad = t.dpf(8f)

    // ---- State ----------------------------------------------------------------------------------------------------
    private var channels: List<Channel> = emptyList()
    private val rows = HashMap<ChannelKey, RowData>()
    private var focusRow = 0
    private var cursor = 0L
    private var windowStart = 0L
    private var scrollY = 0f
    private var windowX = 0f
    private var animator: ValueAnimator? = null
    private var requested: String? = null

    private class RowData(val from: Long, val to: Long, val cells: List<Cell>)

    // ---- Paints ---------------------------------------------------------------------------------------------------
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(13f) * fontScale() }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.semibold; textSize = t.dpf(13f) * fontScale() }
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(12f) * fontScale(); color = t.textMuted; textAlign = Paint.Align.RIGHT }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(12.5f) * fontScale(); color = t.textMuted }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.semibold; textSize = t.dpf(12.5f) * fontScale(); color = t.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t.dpf(1f) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = t.dpf(1.5f) }
    private val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val marker = Path()
    private val rect = RectF()
    private val timeFormat = DateFormat.getTimeFormat(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val now = System.currentTimeMillis()
        windowStart = GuideCells.slotStart(now)
        cursor = now
    }

    private fun fontScale() = resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

    // ---- Data -----------------------------------------------------------------------------------------------------

    /** Shows [list]; focus goes to [focus] (or stays on the same channel, or the first). */
    fun setChannels(list: List<Channel>, focus: ChannelKey?) {
        val keep = focus ?: channels.getOrNull(focusRow)?.key
        channels = list
        focusRow = list.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        // The time window catches up with now (it may have been opened a while ago), unless the viewer had moved
        // ahead into later programmes.
        val now = clock()
        if (cursor < now) cursor = now
        if (windowStart < GuideCells.slotStart(now)) windowStart = GuideCells.slotStart(now)
        windowX = 0f
        scrollY = targetScroll(focusRow).toFloat() * rowH
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
        val key = focusedChannel()?.key
        channels = list
        val i = list.indexOfFirst { it.key == key }
        focusRow = if (i >= 0) i else focusRow.coerceAtMost((list.size - 1).coerceAtLeast(0))
        scrollY = targetScroll(focusRow) * rowH
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
        moveTo(i, animate = false)
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        val fast = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (focusRow > 0) moveTo(focusRow - 1, !fast) else return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (focusRow < channels.size - 1) moveTo(focusRow + 1, !fast) }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveTo((focusRow - visibleRows()).coerceAtLeast(0), !fast)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveTo((focusRow + visibleRows()).coerceAtMost(channels.size - 1), !fast)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(+1, !fast)
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, !fast)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) focusedChannel()?.let { callbacks?.onPick(it, focusedCell()) }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun step(dir: Int, animate: Boolean) {
        val ch = focusedChannel() ?: return
        val cells = cellsOf(ch) ?: return
        val i = GuideCells.at(cells, cursor)
        val now = clock()
        if (dir < 0) {
            val cell = cells.getOrNull(i)
            // From what's on now (or earlier), Left leaves the grid for the groups column.
            if (cell == null || cell.start <= now || i == 0) {
                callbacks?.onLeaveLeft()
                return
            }
            val prev = cells[i - 1]
            cursor = GuideCells.cursorFor(prev, now)
        } else {
            val next = cells.getOrNull(i + 1) ?: return
            cursor = GuideCells.cursorFor(next, now)
        }
        val cell = focusedCell() ?: return
        val target = GuideCells.windowFor(cell, windowStart, windowMs(), GuideCells.slotStart(now))
        if (target != windowStart) {
            // Drawn from where it was (windowX pixels behind the new start), gliding to the new start.
            val from = (target - windowStart) / 60_000f * pxPerMin
            windowStart = target
            if (animate) animateTo(scrollY, from) else windowX = 0f
        }
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    private fun moveTo(row: Int, animate: Boolean) {
        focusRow = row
        val target = targetScroll(row) * rowH
        if (animate && height > 0) animateTo(target, windowX) else { animator?.cancel(); scrollY = target; windowX = 0f }
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** First visible row that keeps the focus row one row inside the edges where it can. */
    private fun targetScroll(row: Int): Int {
        val visible = visibleRows()
        val top = (scrollY / rowH).roundToInt()
        val maxTop = (channels.size - visible).coerceAtLeast(0)
        return when {
            row < top + 1 -> row - 1
            row > top + visible - 2 -> row - visible + 2
            else -> top
        }.coerceIn(0, maxTop)
    }

    private fun animateTo(targetScrollY: Float, fromWindowX: Float) {
        animator?.cancel()
        val startY = scrollY
        val startX = fromWindowX
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                scrollY = startY + (targetScrollY - startY) * f
                windowX = startX * (1f - f)
                invalidate()
            }
            start()
        }
    }

    private fun visibleRows(): Int = ((height - headerH) / rowH).toInt().coerceAtLeast(1)

    private fun windowMs(): Long = (((width - channelW) / pxPerMin) * 60_000).toLong().coerceAtLeast(GuideCells.SLOT_MS)

    private fun cellsOf(ch: Channel): List<Cell>? = rows[ch.key]?.cells

    private fun notifyFocus() {
        val ch = focusedChannel() ?: return
        val cell = focusedCell()
        callbacks?.onFocusMoved(ch, cell)
        val p = cell?.programme
        contentDescription = buildString {
            append(ch.number).append(' ').append(ch.name).append(". ")
            if (p != null) append(p.title).append(", ").append(timeFormat.format(Date(p.start))).append(" to ").append(timeFormat.format(Date(p.stop)))
            else append("No information")
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true && isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /** Asks for listings around what's visible: two screens of rows each way, the window plus a few hours. */
    private fun ensureLoaded() {
        if (channels.isEmpty() || height == 0) return
        val visible = visibleRows()
        val top = (scrollY / rowH).toInt()
        val from = (top - visible * 2).coerceAtLeast(0)
        val to = (top + visible * 3).coerceAtMost(channels.size)
        val tFrom = windowStart - GuideCells.SLOT_MS * 2
        val tTo = windowStart + windowMs() + 4 * 60 * 60_000L
        val missing = channels.subList(from, to).filter { c -> rows[c.key].let { it == null || it.from > windowStart || it.to < windowStart + windowMs() } }
        if (missing.isEmpty()) return
        val key = "${missing.first().key}|${missing.size}|$tFrom"
        if (key == requested) return
        requested = key
        callbacks?.needProgrammes(missing, tFrom, tTo)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollY = targetScroll(focusRow) * rowH
        requested = null
        ensureLoaded()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            // Back in the grid: what's on now, on the same channel.
            val now = clock()
            if (cursor < now) cursor = now
            notifyFocus()
        }
        invalidate()
    }

    // ---- Drawing --------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = clock()
        val gridLeft = channelW
        val ws = windowStart - (windowX / pxPerMin * 60_000).toLong()
        val wEnd = ws + ((width - gridLeft) / pxPerMin * 60_000).toLong()
        fun xOf(time: Long) = gridLeft + (time - ws) / 60_000f * pxPerMin

        drawHeader(canvas, now, ws, wEnd, ::xOf)

        val firstRow = (scrollY / rowH).toInt().coerceAtLeast(0)
        val lastRow = ((scrollY + height - headerH) / rowH).toInt().coerceAtMost(channels.size - 1)
        canvas.save()
        canvas.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        for (r in firstRow..lastRow) {
            val ch = channels[r]
            val top = headerH + r * rowH - scrollY
            drawChannel(canvas, ch, r == focusRow, top)
            canvas.save()
            canvas.clipRect(gridLeft, top, width.toFloat(), top + rowH)
            drawCells(canvas, ch, r == focusRow, top, now, ws, wEnd, ::xOf)
            canvas.restore()
        }
        // The now line over the cells.
        val nx = xOf(now)
        if (nx >= gridLeft) canvas.drawLine(nx, headerH, nx, height.toFloat(), nowPaint)
        canvas.restore()
        if (nx >= gridLeft) canvas.drawCircle(nx, headerH - t.dpf(3f), t.dpf(3f), nowPaint)
    }

    private fun drawHeader(canvas: Canvas, now: Long, ws: Long, wEnd: Long, xOf: (Long) -> Float) {
        val base = headerH - t.dpf(9f)
        val date = DateFormat.format("EEE, MMM d", now).toString() + ", " + timeFormat.format(Date(now))
        canvas.drawText(date, pad, base, datePaint)
        var slot = GuideCells.slotStart(ws)
        canvas.save()
        canvas.clipRect(channelW, 0f, width.toFloat(), headerH)
        while (slot < wEnd) {
            val x = xOf(slot)
            if (x >= channelW - 1) canvas.drawText(timeFormat.format(Date(slot)), x + t.dpf(4f), base, headerPaint)
            slot += GuideCells.SLOT_MS
        }
        canvas.restore()
        fill.color = if (glass) 0x24FFFFFF else 0x1AFFFFFF
        canvas.drawRect(0f, headerH - t.dpf(1f), width.toFloat(), headerH, fill)
    }

    private fun drawChannel(canvas: Canvas, ch: Channel, focusedRow: Boolean, top: Float) {
        val playing = ch.key == playingKey
        val cy = top + rowH / 2
        if (focusedRow && isFocused) {
            fill.color = if (glass) 0x1FFFFFFF else 0x14FFFFFF
            rect.set(t.dpf(2f), top + gap / 2, channelW - gap, top + rowH - gap / 2)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        if (playing) {
            // A small blue play marker: this is the channel playing.
            val s = t.dpf(5f)
            val x = t.dpf(6f)
            marker.reset()
            marker.moveTo(x, cy - s); marker.lineTo(x + s * 1.6f, cy); marker.lineTo(x, cy + s); marker.close()
            fill.color = t.accent
            canvas.drawPath(marker, fill)
        }
        numberPaint.color = if (playing) t.accent else t.textMuted
        canvas.drawText(ch.number.toString(), numberW, baseline(cy, numberPaint), numberPaint)
        val lx = numberW + t.dpf(8f)
        rect.set(lx, cy - logoH / 2, lx + logoW, cy + logoH / 2)
        val bmp = ch.logo?.let { callbacks?.logo(it) }
        if (bmp != null) {
            val s = minOf(logoW / bmp.width, logoH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            rect.set(lx + (logoW - w) / 2, cy - h / 2, lx + (logoW + w) / 2, cy + h / 2)
            canvas.drawBitmap(bmp, null, rect, logoPaint)
        } else {
            fill.color = 0x26FFFFFF
            canvas.drawRoundRect(rect, t.dpf(4f), t.dpf(4f), fill)
        }
        val nx = lx + logoW + t.dpf(10f)
        namePaint.color = if (playing) t.accent else t.text
        val name = TextUtils.ellipsize(ch.name, namePaint, channelW - nx - t.dpf(6f), TextUtils.TruncateAt.END)
        canvas.drawText(name, 0, name.length, nx, baseline(cy, namePaint), namePaint)
    }

    private fun drawCells(canvas: Canvas, ch: Channel, focusedRow: Boolean, top: Float, now: Long, ws: Long, wEnd: Long, xOf: (Long) -> Float) {
        val cy = top + rowH / 2
        val row = rows[ch.key]
        if (row == null) {
            // Not loaded yet: one faint bar, no text.
            fill.color = if (glass) 0x0FFFFFFF else 0x801A1E24.toInt()
            rect.set(channelW + gap / 2, top + gap / 2, width - gap / 2, top + rowH - gap / 2)
            canvas.drawRoundRect(rect, radius, radius, fill)
            return
        }
        val focusIndex = if (focusedRow) GuideCells.at(row.cells, cursor) else -1
        for ((i, cell) in row.cells.withIndex()) {
            if (cell.stop <= ws || cell.start >= wEnd) continue
            val x0 = maxOf(xOf(cell.start), channelW) + gap / 2
            val x1 = minOf(xOf(cell.stop), width.toFloat()) - gap / 2
            if (x1 - x0 < 1f) continue
            rect.set(x0, top + gap / 2, x1, top + rowH - gap / 2)
            val focused = i == focusIndex && isFocused
            val selected = i == focusIndex && !isFocused
            val onAir = cell.contains(now)
            val past = cell.stop <= now
            fill.color = when {
                focused -> t.focusFill
                glass && selected -> 0x38FFFFFF
                glass && onAir -> 0x29FFFFFF
                glass -> 0x17FFFFFF
                selected -> 0xF2343B47.toInt()
                onAir -> 0xF2262B33.toInt()
                else -> t.rowFill
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (glass && !focused) {
                rim.color = 0x1FFFFFFF
                canvas.drawRoundRect(rect, radius, radius, rim)
            }
            val title = cell.programme?.title?.takeIf { it.isNotEmpty() } ?: if (cell.programme == null) "No information" else "Untitled"
            textPaint.typeface = if (focused || onAir) t.semibold else t.regular
            textPaint.color = when {
                focused -> t.accent
                cell.programme == null || past -> 0x99F4F2EE.toInt()
                else -> t.text
            }
            val avail = (x1 - x0 - t.dpf(16f)).toInt()
            if (avail > t.dpf(12f)) {
                val text = if (cell.cachedWidth == avail && cell.cachedText != null && !focused) cell.cachedText!! else
                    TextUtils.ellipsize(title, textPaint, avail.toFloat(), TextUtils.TruncateAt.END).also { cell.cachedText = it; cell.cachedWidth = avail }
                canvas.drawText(text, 0, text.length, x0 + t.dpf(8f), baseline(cy, textPaint), textPaint)
            }
        }
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
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_ROWS = 600
    }
}
