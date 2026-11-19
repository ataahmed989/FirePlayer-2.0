package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Position
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRulesTest {
    private val target = SourceTarget(Fixtures.film)

    @Test
    fun theTitlePageAsksSoonerWithAResumePointOrFromNextEpisodes() {
        assertEquals(LookupWhy.PAGE, PlayRules.pageLookup(hasResumePoint = false, soon = false))
        assertEquals(LookupWhy.PAGE_SOON, PlayRules.pageLookup(hasResumePoint = true, soon = false))
        assertEquals(LookupWhy.PAGE_SOON, PlayRules.pageLookup(hasResumePoint = false, soon = true))
    }

    @Test
    fun startIsExplicitAndFollowsTheResumeRule() {
        val resumable = Position(600_000, 7_200_000, 0)
        assertEquals(StartAt.At(600_000), PlayRules.startAt(resumable, fromBeginning = false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(resumable, fromBeginning = true))
        // Under 30 s, or in the last 3 minutes: from the beginning.
        assertEquals(StartAt.Beginning, PlayRules.startAt(Position(20_000, 7_200_000, 0), false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(Position(7_100_000, 7_200_000, 0), false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(null, false))
        assertEquals(StartAt.At(600_000), PlayRules.startAt(LastPlayed(null, resumable)))
        assertEquals(StartAt.Beginning, PlayRules.startAt(LastPlayed(null, resumable, markedWatched = true)))
    }

    @Test
    fun theStripSaysWhoIsStillLookingAndWhatTheKeysDo() {
        val now = 100_000L
        val a = Fixtures.source(1)
        val list = Fixtures.list(target, listOf(a), states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(33),
            Addon.MEDIAFUSION to AddonState.Asking(since = now - 9_000, deadline = now + 16_000),
        ))
        assertEquals("Torrentio ✓ 33 · MediaFusion still looking 9 s of 25 · OK: play best so far · Back: cancel", PlayRules.strip(list, now))
        assertEquals("Waiting for MediaFusion · 4 s", PlayRules.waitingLabel(list, now, pressedAt = now - 4_000))
        assertEquals(9f / 25f, PlayRules.waitFraction(list, now), 0.001f)
        // Nothing to play yet: no "OK" in the strip.
        val none = Fixtures.list(target, emptyList(), states = list.states, best = null)
        assertFalse(PlayRules.strip(none, now).contains("OK"))
    }

    @Test
    fun stateWordsForEveryAddonState() {
        assertNull(PlayRules.stateText(Addon.TORRENTIO, AddonState.NotSet, 0))
        assertEquals("MediaFusion didn't answer", PlayRules.stateText(Addon.MEDIAFUSION, AddonState.Failed("x"), 0))
        assertEquals("MediaFusion busy", PlayRules.stateText(Addon.MEDIAFUSION, AddonState.Busy(5), 0))
        assertEquals("Torrentio still looking", PlayRules.stateText(Addon.TORRENTIO, AddonState.NotAsked, 0))
    }

    @Test
    fun theWaitEndsWhenCompleteOrEveryLimitHasPassed() {
        val now = 100_000L
        val asking = Fixtures.list(target, emptyList(), states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(0),
            Addon.MEDIAFUSION to AddonState.Asking(since = now - 25_000, deadline = now),
        ))
        assertFalse(PlayRules.waitedOut(asking, now))
        assertFalse(PlayRules.waitedOut(asking, now + PlayRules.GRACE_MS))
        assertTrue(PlayRules.waitedOut(asking, now + PlayRules.GRACE_MS + 1))
        assertTrue(PlayRules.waitedOut(Fixtures.list(target, emptyList()), now))
        // An add-on not asked yet (budget) holds the wait like one asking.
        val notAsked = Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.NotAsked))
        assertFalse(PlayRules.waitedOut(notAsked, now))
    }

    @Test
    fun theSourceLineNamesWhatPlayWouldStartAndWhereItsFactsCameFrom() {
        val best = Fixtures.source(1)
        assertNull(PlayRules.sourceLine(null))
        val done = Fixtures.list(target, listOf(best))
        assertEquals("Best for this TV · 4K · HDR · WEB-DL · 29.3 GB · ≈25 Mb/s · from Torrentio", PlayRules.sourceLine(done))
        val soFar = done.copy(states = mapOf(Addon.TORRENTIO to AddonState.Answered(1), Addon.MEDIAFUSION to AddonState.Asking(0, 25_000)))
        assertTrue(PlayRules.sourceLine(soFar)!!.startsWith("Best so far · "))
        val last = Fixtures.source(2, also = Addon.MEDIAFUSION)
        assertTrue(PlayRules.sourceLine(done.copy(lastUsed = last))!!.let { it.startsWith("Last used · ") && it.endsWith("from Both add-ons") })
        assertEquals("No sources found", PlayRules.sourceLine(Fixtures.list(target, emptyList())))
        val notReady = Fixtures.source(3, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
        assertEquals("None ready on Real-Debrid · 1 to fetch in Sources", PlayRules.sourceLine(Fixtures.list(target, listOf(notReady))))
        val noAddons = Fixtures.list(target, emptyList(), states = Addon.entries.associateWith { AddonState.NotSet })
        assertEquals(PlayRules.NO_ADDONS_LINE, PlayRules.sourceLine(noAddons))
        // Nobody answered yet: the line stays empty.
        assertNull(PlayRules.sourceLine(Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.Asking(0, 10_000)))))
    }

    @Test
    fun theSourcesButtonCountsOnceAnAddonAnswered() {
        assertEquals("Sources", PlayRules.sourcesLabel(null))
        assertEquals("Sources", PlayRules.sourcesLabel(Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.Asking(0, 1)))))
        assertEquals("Sources · 2", PlayRules.sourcesLabel(Fixtures.list(target, listOf(Fixtures.source(1), Fixtures.source(2)))))
    }

    @Test
    fun aSourcesTwoLinesCarryNoLinkAndOneWarning() {
        val s = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), dvProfile = 7, pack = true), copies = 3,
                warning = "Dolby Vision 7: may not play here")
        }
        assertEquals("4K · DV7 · HDR · WEB-DL · 29.3 GB · ≈25 Mb/s · Pack · ×3", PlayRules.badges(s))
        assertEquals("⚠ Dolby Vision 7: may not play here · HEVC 10-bit · DD+ 5.1 · GRP · EN", PlayRules.facts(s))
        assertFalse(PlayRules.badges(s).contains("invalid"))
        assertFalse(PlayRules.facts(s).contains("invalid"))
        // Seeders only for files not on Real-Debrid.
        val nr = Fixtures.source(2, readiness = Readiness.NOT_READY).let { it.copy(facts = it.facts.copy(seeders = 12)) }
        assertTrue(PlayRules.facts(nr).endsWith("12 seeders"))
        assertEquals("from Torrentio", PlayRules.factsFrom(Fixtures.source(1)) { "Nov 12" })
    }

    @Test
    fun sizesClocksAndSwitchPositions() {
        assertEquals("29.3 GB", PlayRules.size(29_300_000_000L))
        assertEquals("870 MB", PlayRules.size(870_000_000L))
        assertEquals("1:02:03", PlayRules.clock(3_723_000L))
        assertEquals("42:10", PlayRules.clock(2_530_000L))
        assertEquals(55_000L, PlayRules.switchStart(60_000L))
        assertEquals(0L, PlayRules.switchStart(3_000L))
        assertEquals("4K", PlayRules.resolution(2160))
        assertEquals("720p", PlayRules.resolution(720))
        assertNull(PlayRules.resolution(null))
    }

    @Test
    fun theNextEpisodeIsTheNextAiredRegularOneAcrossSeasons() {
        fun ep(s: Int, e: Int, aired: Long? = 1L) = Episode(Episode.idOf("tt1", s, e), s, e, "E$e", released = aired)
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(2, 2, aired = 10_000L), ep(0, 1))
        assertEquals(EpisodeRef(1, 2, "E2"), PlayRules.nextEpisode(eps, EpisodeRef(1, 1), 5_000L)?.ref)
        assertEquals(EpisodeRef(2, 1, "E1"), PlayRules.nextEpisode(eps, EpisodeRef(1, 2), 5_000L)?.ref)
        // Not aired yet, the end, or a special: none.
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(2, 1), 5_000L))
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(2, 2), 50_000L))
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(0, 1), 5_000L))
    }
}
