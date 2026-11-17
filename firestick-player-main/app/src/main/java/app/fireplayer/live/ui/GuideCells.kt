package app.fireplayer.live.ui

import app.fireplayer.live.Programme

/**
 * One cell of a guide row: a programme, or a gap with no listing ("No information"). A row's cells cover its whole
 * loaded time range with no holes and no overlaps, so the D-pad always has somewhere to go.
 */
class Cell(val start: Long, val stop: Long, val programme: Programme?) {
    /** The title drawn last time, ellipsized for [cachedWidth] pixels (drawing reuses it while the width holds). */
    var cachedText: CharSequence? = null
    var cachedWidth = -1

    fun contains(t: Long) = t in start until stop
    override fun toString() = "Cell($start-$stop ${programme?.title ?: "-"})"
}

/** Pure guide arithmetic, unit-tested: cells of a row, focus movement, and the time window. */
object GuideCells {
    const val SLOT_MS = 30 * 60 * 1000L

    /** The start of the 30-minute slot holding [t] (the guide's left edge at "now"). */
    fun slotStart(t: Long): Long = t - Math.floorMod(t, SLOT_MS)

    /**
     * The cells for one row over [from, to): its programmes (sorted, clipped of overlaps: a programme that starts
     * before the previous one ends is shortened to start where it ends), with gap cells wherever nothing is listed.
     */
    fun cells(programmes: List<Programme>, from: Long, to: Long): List<Cell> {
        if (to <= from) return emptyList()
        val out = ArrayList<Cell>()
        var t = from
        for (p in programmes.sortedBy { it.start }) {
            if (p.stop <= t) continue
            if (p.start >= to) break
            val s = maxOf(p.start, t)
            if (s > t) out += Cell(t, s, null)
            val e = minOf(p.stop, to)
            if (e > s) {
                // The cell tiles the row from [s]; the programme keeps its true times for display.
                out += Cell(s, e, p)
                t = e
            }
        }
        if (t < to) out += Cell(t, to, null)
        return out
    }

    /** The cell holding [t], else the nearest one. */
    fun at(cells: List<Cell>, t: Long): Int {
        if (cells.isEmpty()) return -1
        val i = cells.indexOfFirst { it.contains(t) }
        if (i >= 0) return i
        return if (t < cells.first().start) 0 else cells.size - 1
    }

    /**
     * Where the focus time goes when a cell gets focus: "now" for the programme on air (so moving up and down keeps
     * to what's on now), else the cell's start.
     */
    fun cursorFor(cell: Cell, now: Long): Long = if (cell.contains(now)) now else cell.start

    /**
     * The left edge of the visible time window that keeps [cell] in view: the window moves in 30-minute steps, never
     * earlier than [earliest] (the slot holding now), and only as far as needed.
     */
    fun windowFor(cell: Cell, windowStart: Long, windowMs: Long, earliest: Long): Long {
        var w = windowStart
        // Right: the cell's start must be inside the window with at least a slot of it showing.
        while (cell.start >= w + windowMs - SLOT_MS / 2) w += SLOT_MS
        // Left: back until the cell's start (or now) is showing.
        while (w > earliest && maxOf(cell.start, earliest) < w) w -= SLOT_MS
        return maxOf(w, earliest)
    }
}
