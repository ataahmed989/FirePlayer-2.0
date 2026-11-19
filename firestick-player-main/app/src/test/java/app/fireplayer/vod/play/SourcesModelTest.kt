package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesModelTest {
    private val target = SourceTarget(Fixtures.film)
    private val uhd = (1..7).map { Fixtures.source(it) }
    private val fhd = (11..12).map { Fixtures.source(it, group = SourceGroup.FHD, height = 1080) }
    private val low = (21..23).map { Fixtures.source(it, group = SourceGroup.LOWER, height = 720) }
    private val notReady = Fixtures.source(31, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
    private val list = Fixtures.list(target, uhd + fhd + low + notReady, lastUsed = Fixtures.source(12, group = SourceGroup.FHD, height = 1080))

    private fun keys(items: List<SourcesModel.Item>) = items.map { it.key }

    @Test
    fun groupsComeInOrderWithFiveRowsOrClosed() {
        val items = SourcesModel.items(list, 0, emptySet())
        val k = keys(items)
        assertEquals(listOf("note:status", "note:totals", "note:ready", "head:best", "src:best:k12", "src:best:k1", "head:UHD"), k.take(7))
        // 4K: 5 rows, then Show 2 more.
        assertEquals((1..5).map { "src:UHD:k$it" } + "more:UHD", k.subList(7, 13))
        assertEquals("Show 2 more", items[12].label)
        // 1080p fits; 720p and lower starts closed; Not on Real-Debrid closed with its note.
        assertTrue(k.containsAll(listOf("head:FHD", "src:FHD:k11", "src:FHD:k12", "head:LOWER", "more:LOWER", "head:NOT_ON_RD", "note:NOT_ON_RD", "more:NOT_ON_RD")))
        assertFalse(k.any { it.startsWith("src:LOWER") })
        assertEquals("Show 3", items.first { it.key == "more:LOWER" }.label)
        assertEquals("720p and lower · 3", items.first { it.key == "head:LOWER" }.label)
        // Every source row's text is the add-ons' words, never a link.
        assertFalse(items.any { (it.label + it.note + it.value).contains("invalid") })
    }

    @Test
    fun openingAGroupListsAllOfIt() {
        val k = keys(SourcesModel.items(list, 0, setOf(SourceGroup.UHD, SourceGroup.LOWER)))
        assertTrue(k.containsAll((1..7).map { "src:UHD:k$it" } + (21..23).map { "src:LOWER:k$it" }))
        assertFalse(k.contains("more:UHD"))
        assertFalse(k.contains("more:LOWER"))
    }

    @Test
    fun rowStatesBestLastUsedPlayingAndTried() {
        val attempts = listOf(Attempt(uhd[1], 0L, OpenWhy.START, Failure(FailureKind.FILE)))
        val items = SourcesModel.items(list, 0, emptySet(), attempts, playingKey = "k3", time = { "20:41" })
        fun value(key: String) = items.first { it.key == key }.value
        assertEquals("Last used\nfrom Torrentio", value("src:best:k12"))
        assertEquals("Best\nfrom Torrentio", value("src:best:k1"))
        assertEquals("Tried 20:41 · didn't play\nfrom Torrentio", value("src:UHD:k2"))
        assertEquals("Playing\nfrom Torrentio", value("src:UHD:k3"))
        assertTrue(items.first { it.key == "src:UHD:k3" }.chosen)
    }

    @Test
    fun whileLookingTheBestIsSoFarAndTheStatusSaysWho() {
        val soFar = list.copy(states = mapOf(Addon.TORRENTIO to AddonState.Answered(13), Addon.MEDIAFUSION to AddonState.Asking(0, 25_000)))
        val items = SourcesModel.items(soFar, 9_000, emptySet())
        assertEquals("Torrentio ✓ 13 · MediaFusion still looking 9 s of 25", items[0].label)
        assertTrue(items.first { it.key == "src:best:k1" }.value!!.startsWith("Best so far"))
    }

    @Test
    fun emptyAndUnaskedLists() {
        assertEquals(listOf("note:looking"), keys(SourcesModel.items(null, 0, emptySet())))
        val none = SourcesModel.items(Fixtures.list(target, emptyList()), 0, emptySet())
        assertEquals(listOf("note:status", "note:none"), keys(none))
        assertEquals("No sources found.", none[1].label)
    }

    @Test
    fun detailsHaveTheFactsAndEachAddonsWordsButNoLink() {
        val both = Fixtures.source(5, also = Addon.MEDIAFUSION)
        val d = SourcesModel.details(both) { "Nov 12" }
        val text = d.joinToString("\n") { "${it.first}: ${it.second}" }
        assertTrue(text.contains("Torrentio says: Release.5.2160p.WEB-DL"))
        assertTrue(text.contains("MediaFusion says: Release 5"))
        assertTrue(text.contains("Offered by: Both add-ons"))
        assertTrue(text.contains("Probably on Real-Debrid"))
        assertFalse(text.contains("invalid"))
        assertTrue(SourcesModel.asksFirst(notReady))
        assertFalse(SourcesModel.asksFirst(both))
    }
}
