package app.fireplayer.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [PacedVideoRenderer]'s timing, apart from Media3 so it can be tested: each frame goes to the TV's refresh after the one
 * before, the refreshes timed from when frames reached the screen.
 *
 * It also checks those times against the refresh measured. On a TV at the measured rate, frames reach the screen a whole
 * number of refreshes apart. Once, after the TV came on at 59.94 Hz in the middle of a film, frames went on being paced
 * for the 23.976 Hz measured before: they reached the screen 1, 4 or 5 of its refreshes apart instead of 2 and 3 in turn,
 * and some never showed. When a third of the latest frames fall between the refreshes measured, frames go out on
 * Media3's own times instead, until a different refresh, or another mode, is measured, or the screen agrees again: frames
 * sent on Media3's times still reach the screen on its refreshes, so they're judged all the same, and when nearly all of
 * them fit the refresh measured again (the TV was off or reconnecting for a moment, on the same mode) they're paced again.
 *
 * [mode] in the calls below counts the modes measured in turn ([VsyncFix.modes]): a different count means the TV has
 * been on another mode since.
 *
 * Media3's times wobble from frame to frame (its clock is the sound's), by 10 to 15 ms at times. Judged one frame at a
 * time, a film whose times sat near half a refresh from the TV's refreshes was moved a refresh later and a refresh
 * earlier in turn: a frame held for two refreshes, then two frames on one refresh (only one of them shows), over and
 * over, one frame in six or seven lost in a model of it. So the frames are moved by an average of how far they've been
 * from their refreshes, not by a single frame: a wobble evens out, while a film whose rate isn't quite the TV's drifts
 * the average along until a frame is moved, as it must be. A frame a whole refresh or more away (one before it was
 * dropped, or never decoded) is followed at once.
 *
 * Each frame's [move] says what was done with it, for the counts of frames lost ([PacedVideoRenderer], [FrameCounts]).
 *
 * All on the playback thread.
 */
class FramePacer {

    private var refreshNs = 0L   // when a frame last reached the screen: one of the TV's refreshes
    private var lastTargetNs = 0L
    // How far behind its refresh a frame has been lately (ahead when below 0), in ns: an average of the latest frames,
    // each counting for 1/SMOOTHING of it. Started afresh wherever lastTargetNs is.
    private var behindNs = 0.0
    private var previousNs = 0L
    private var judgedPeriodNs = 0L
    private var judgedMode = 0
    private var fits = 0
    private var misfits = 0

    /** What [paced] did with the latest frame: [MEDIA3], [START], [NEXT], [LATER], [SAME] or [FOLLOWED]. */
    var move = MEDIA3
        private set

    /** A refresh length the screen disagreed with: frames go out on Media3's times while it's the one measured. */
    var offGridPeriodNs = 0L
        private set
    private var offGridMode = 0

    private fun offGrid(periodNs: Long, mode: Int) = periodNs == offGridPeriodNs && mode == offGridMode

    /** A new decoder: nothing known of the screen yet. */
    fun reset() {
        refreshNs = 0L
        lastTargetNs = 0L
        behindNs = 0.0
        move = MEDIA3
        previousNs = 0L
        judgedPeriodNs = 0L
        fits = 0
        misfits = 0
        offGridPeriodNs = 0L
    }

    /**
     * A frame reached the screen at [nanoTime]. Returns true when, from now on, the screen disagrees with [periodNs]
     * (the refresh measured while [unaligned]). When it disagreed before and fits again, [offGridPeriodNs] is cleared.
     */
    fun rendered(nanoTime: Long, unaligned: Boolean, periodNs: Long, mode: Int): Boolean {
        refreshNs = nanoTime
        val previous = previousNs
        previousNs = nanoTime
        if (!unaligned || periodNs <= 0L || previous == 0L) return false
        val d = nanoTime - previous
        // Not after a pause or a jump.
        if (d <= 0L || d > JUDGED_GAP * periodNs) return false
        if (periodNs != judgedPeriodNs || mode != judgedMode) {
            judgedPeriodNs = periodNs
            judgedMode = mode
            fits = 0
            misfits = 0
        }
        // How far from a whole number of refreshes apart.
        val off = abs(Math.floorMod(d + periodNs / 2, periodNs) - periodNs / 2)
        if (off > periodNs / 8) misfits++ else fits++
        if (fits + misfits < JUDGED) return false
        val disagrees = misfits >= MISFITS
        val agrees = misfits <= AGREES
        fits = 0
        misfits = 0
        if (offGrid(periodNs, mode)) {
            if (agrees) offGridPeriodNs = 0L
            return false
        }
        if (disagrees) {
            offGridPeriodNs = periodNs
            offGridMode = mode
        }
        return disagrees
    }

    /**
     * When to send out a frame Media3 would send at [releaseTimeNs]. [move] then says what was done with it. When it's
     * [SAME] the frame would only take the place of the one before on the screen, so it isn't sent at all
     * ([PacedVideoRenderer] hands it back to the decoder), and the next frame goes to the refresh after the one before,
     * as if this one hadn't come.
     */
    fun paced(releaseTimeNs: Long, unaligned: Boolean, periodNs: Long, mode: Int): Long {
        val refresh = refreshNs
        if (!unaligned || periodNs <= 0L || refresh == 0L || offGrid(periodNs, mode)) {
            lastTargetNs = 0L
            behindNs = 0.0
            move = MEDIA3
            return releaseTimeNs
        }
        val last = lastTargetNs
        var target: Long
        if (last == 0L || abs(releaseTimeNs - last) > 3 * periodNs) {
            // Starting, or after a jump or a pause: the refresh nearest the film's time
            target = refresh + ((releaseTimeNs - refresh).toDouble() / periodNs).roundToLong() * periodNs
            behindNs = 0.0
            move = START
        } else {
            // Kept on the TV's refreshes, where the screen says they are
            val off = Math.floorMod(last - refresh, periodNs)
            val onGrid = last - if (off > periodNs / 2) off - periodNs else off
            target = onGrid + periodNs
            val behind = releaseTimeNs - target
            if (abs(behind) >= FOLLOW * periodNs) {
                // A whole refresh or more off: a frame before this one was dropped or never decoded (or Media3's clock
                // moved). Followed at once, and the average starts afresh from there.
                target += (behind.toDouble() / periodNs).roundToLong() * periodNs
                behindNs = 0.0
                move = if (target <= onGrid) SAME else FOLLOWED
            } else {
                // Moved a refresh only when the frames have been that far off for a while, not for one frame's wobble;
                // the move is taken off the average, which is measured from the new refreshes from then on.
                behindNs += (behind - behindNs) / SMOOTHING
                move = NEXT
                if (behindNs > SLACK * periodNs) {
                    target += periodNs
                    behindNs -= periodNs
                    move = LATER
                } else if (behindNs < -SLACK * periodNs) {
                    target -= periodNs
                    behindNs += periodNs
                    move = SAME
                }
            }
            if (move == SAME) {
                // Not sent: the frame before keeps its refresh, and the next frame goes to the one after.
                lastTargetNs = onGrid
                return target - periodNs / 2
            }
        }
        lastTargetNs = target
        // Half a refresh early: the TV shows it at that refresh, whichever way the timing wobbles
        return target - periodNs / 2
    }

    companion object {
        /** Frames go out on Media3's own times: they aren't paced. */
        const val MEDIA3 = 0
        /** The first frame, or the first after a jump or a pause: on the refresh nearest its time. */
        const val START = 1
        /** On the refresh after the frame before's: the usual. */
        const val NEXT = 2
        /** A refresh later than that (+1 refresh): the frame before stays on the screen for two refreshes. */
        const val LATER = 3
        /** On the frame before's refresh (−1 refresh, or further back): it would only replace that one, so it's skipped. */
        const val SAME = 4
        /** Followed at once a refresh or more later: a frame before it didn't come (dropped, or never decoded). */
        const val FOLLOWED = 5

        /** How far, in refreshes, the frames' average must stray from their refresh before they're moved by one. */
        const val SLACK = 0.6
        /** A single frame this far off, in refreshes, is followed at once, without waiting for the average. */
        const val FOLLOW = 0.9
        /** Each frame counts for 1/SMOOTHING of the average. */
        const val SMOOTHING = 16
        /** Frames judged at a time. */
        const val JUDGED = 24
        /** Of those, how many between refreshes means the screen disagrees: a third, so a stray late frame can't. */
        const val MISFITS = 8
        /**
         * After the screen disagreed, at most this many between refreshes means it agrees again: half of [MISFITS], so a
         * screen near the line isn't paced and let go in turn.
         */
        const val AGREES = 4
        /** Frames further apart than this many refreshes (a pause, a jump) aren't judged. */
        const val JUDGED_GAP = 8
    }
}
