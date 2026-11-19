package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The film buffer by free memory, what's kept behind held to a size, and the download speed for the log. */
class BufferAndRateTest {

    private val mib = 1L shl 20
    private val stickHeap = 384 * mib

    // ---- The buffer by the memory free at the start (FilmMemory.plan)

    @Test fun plentyFreeLeavesTheTiersBudget() {
        val plan = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 600 * mib)
        assertEquals((128 * mib).toInt(), plan.budgetBytes)
        assertFalse(plan.byFreeMemory)
        assertEquals(6_000, plan.backBufferMs)
        assertEquals(24 * mib, plan.backCapBytes)
    }

    @Test fun littleFreeHoldsTheBufferDownButNotUnderItsFloor() {
        // 340 MiB free: 260 are left for the decoder's frames and the rest of the TV.
        val tight = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 340 * mib)
        assertEquals((80 * mib).toInt(), tight.budgetBytes)
        assertTrue(tight.byFreeMemory)
        // The Stick as measured mid-film (64 to 152 MB free): the floor, never nothing.
        val starved = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 100 * mib)
        assertEquals(FilmMemory.FREE_FLOOR_BYTES, starved.budgetBytes)
        assertTrue(starved.byFreeMemory)
        // Held down to the tiny tier's size, it keeps behind what that tier keeps.
        assertEquals(FilmMemory.LEAST_BACK_BUFFER_MS, starved.backBufferMs)
        assertEquals(16 * mib, starved.backCapBytes)
    }

    @Test fun freeMemoryNotKnownIsTheTiersBudgetAsBefore() {
        assertEquals(FilmMemory.plan(RamTier.SMALL, stickHeap), FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 0L))
        assertEquals((128 * mib).toInt(), FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = -1L).budgetBytes)
    }

    @Test fun freeMemoryNeverRaisesABudgetAndTheBigTierHasNoBackCap() {
        val big = FilmMemory.plan(RamTier.LARGE, 1024 * mib, freeBytes = 4096 * mib)
        assertEquals((256 * mib).toInt(), big.budgetBytes)
        assertEquals(0L, big.backCapBytes)
        // The tiny tier's own budget is under the free-memory floor: it isn't raised to it.
        assertEquals((64 * mib).toInt(), FilmMemory.plan(RamTier.TINY, stickHeap, freeBytes = 50 * mib).budgetBytes)
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.plan(RamTier.TINY, 32 * mib, freeBytes = 50 * mib).budgetBytes)
    }

    // ---- What's kept behind, held to a size (BackBuffer.keepUs)

    private val sixSeconds = 6_000_000L

    @Test fun an84MbpsFilmKeepsAbout2Point3SecondsBehindIn24MiB() {
        // 84 Mb/s: 10.5 MB a second. 12 s of it held is 126 MB.
        val held = 126_000_000L
        val keep = BackBuffer.keepUs(24 * mib, held, 12_000_000L, sixSeconds)
        assertEquals(2_396_745L, keep)
    }

    @Test fun aFilmUnderTheCapsRateKeepsTheWholeLength() {
        // 25 Mb/s: 6 s are 19 MB, under 24 MiB.
        val held = 25_000_000L / 8 * 30
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, held, 30_000_000L, sixSeconds))
    }

    @Test fun neverLessThanTwoSecondsAndNothingBeforeTheSizeIsKnown() {
        // 300 Mb/s (no such film, but the floor holds).
        assertEquals(BackBuffer.LEAST_KEPT_US, BackBuffer.keepUs(24 * mib, 375_000_000L, 10_000_000L, sixSeconds))
        // Too little held to tell the film's size by: Media3's own length.
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, 30_000_000L, 2_000_000L, sixSeconds))
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, 0L, 10_000_000L, sixSeconds))
        // No cap: as Media3 has it.
        assertEquals(sixSeconds, BackBuffer.keepUs(0L, 126_000_000L, 12_000_000L, sixSeconds))
        // A length already at the least isn't cut further.
        assertEquals(2_000_000L, BackBuffer.keepUs(mib, 126_000_000L, 12_000_000L, 2_000_000L))
    }

    // ---- The download's speed (DownloadRate)

    @Test fun everySecondCountsButOnlyFreeOnesSayWhatTheLineCarries() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        // Three seconds loading freely at 300 Mb/s (37.5 MB a second).
        repeat(3) {
            t += 1_000
            bytes += 37_500_000
            r.sample(t, bytes, free = true)
        }
        // Then the buffer is full: seven seconds at the film's own 84 Mb/s.
        repeat(7) {
            t += 1_000
            bytes += 10_500_000
            r.sample(t, bytes, free = false)
        }
        assertEquals(148.8, r.mbps, 0.05)
        assertEquals(300.0, r.freeMbps, 0.05)
        assertEquals(3, r.freeSeconds)
        assertEquals(300.0, r.bestFreeMbps, 0.05)
        // The next line starts afresh; the best stays.
        r.take()
        assertEquals(0.0, r.mbps, 0.0)
        assertEquals(0, r.freeSeconds)
        assertEquals(300.0, r.bestFreeMbps, 0.05)
    }

    @Test fun theBestNeedsThreeFreeSecondsInARow() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        for (free in listOf(true, true, false, true, true)) {
            t += 1_000
            bytes += 25_000_000
            r.sample(t, bytes, free)
        }
        assertEquals(0.0, r.bestFreeMbps, 0.0)
        t += 1_000
        bytes += 25_000_000
        r.sample(t, bytes, free = true)
        assertEquals(200.0, r.bestFreeMbps, 0.05)
        // A slower stretch later doesn't lower it.
        repeat(4) {
            t += 1_000
            bytes += 5_000_000
            r.sample(t, bytes, free = true)
        }
        assertEquals(200.0, r.bestFreeMbps, 0.05)
    }

    @Test fun aNewConnectionsCountOrALongGapIsntARate() {
        val r = DownloadRate()
        r.sample(0, 1_000_000, free = true)
        r.sample(1_000, 2_000_000, free = true)
        // The count starting over (another player): nothing is added for that second.
        r.sample(2_000, 500, free = true)
        // Away for a minute.
        r.sample(62_000, 90_000_000, free = true)
        assertEquals(8.0, r.mbps, 0.01)
        r.reset()
        assertEquals(0.0, r.bestFreeMbps, 0.0)
        assertEquals(0.0, r.mbps, 0.0)
    }
}
