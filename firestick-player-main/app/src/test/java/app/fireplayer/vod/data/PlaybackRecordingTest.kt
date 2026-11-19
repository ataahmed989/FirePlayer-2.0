package app.fireplayer.vod.data

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.sources.RecordedLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** What playback records (step 3): the release last used, watched past 90 % or into the credits, Next Episodes. */
class PlaybackRecordingTest {
    private var now = TitlePageFixtures.NOW
    private val direct = Executor { it.run() }
    private val store = MemoryLibraryStore()
    private var changes = 0
    private val library = VodLibrary(store, { now }, direct) { changes++ }
    private val recorded = RecordedLibrary(library)
    private val film = Title("tt0000001", Kind.MOVIE, "Film")
    private val show = Title("tt1695360", Kind.SERIES, "The Legend of Korra")
    private val hash = "a".repeat(40)

    @Test
    fun theLastReleaseIsKeptByTitleAndEpisodeWithoutALink() {
        assertNull(recorded.lastRelease(film, null))
        recorded.recordRelease(film, null, LastRelease(Addon.MEDIAFUSION, ReleaseKey(hash, 3), now))
        recorded.recordRelease(show, EpisodeRef(2, 5), LastRelease(Addon.TORRENTIO, ReleaseKey(hash, null), now))
        assertEquals(LastRelease(Addon.MEDIAFUSION, ReleaseKey(hash, 3), now), recorded.lastRelease(film, null)?.copy(at = now))
        assertEquals(ReleaseKey(hash, null), recorded.lastRelease(show, EpisodeRef(2, 5))?.release)
        assertNull(recorded.lastRelease(show, EpisodeRef(2, 6)))
        // Stored as hash, index and add-on name only, and read back by a new library.
        val row = store.releases.values.first { it.id == film.id }
        assertEquals(listOf("MEDIAFUSION", hash, "3"), listOf(row.addon, row.hash, row.fileIdx.toString()))
        assertEquals("tt1695360:2:5", store.releases.values.first { it.kind == Kind.SERIES }.id)
        val again = RecordedLibrary(VodLibrary(store, { now }, direct) { })
        assertEquals(Addon.TORRENTIO, again.lastRelease(show, EpisodeRef(2, 5))?.addon)
    }

    @Test
    fun past90PercentOrIntoTheCreditsCountsAsWatched() {
        recorded.recordPlayback(film, null, 50 * 60_000L, 120 * 60_000L, creditsAtMs = null)
        assertFalse(library.isWatched(film.id))
        assertTrue(library.inContinueWatching(film.id))
        now += 1000
        // Into the credits (at 1:45 of 2:00) but under 90 %.
        recorded.recordPlayback(film, null, 106 * 60_000L, 120 * 60_000L, creditsAtMs = 105 * 60_000L)
        assertTrue(library.isWatched(film.id))
        assertFalse(library.inContinueWatching(film.id))
        // An episode past 90 %.
        val other = Title("tt0000009", Kind.SERIES, "Other")
        recorded.recordPlayback(other, EpisodeRef(1, 1), 41 * 60_000L, 45 * 60_000L, creditsAtMs = null)
        assertFalse(library.inContinueWatching(other.id))
        assertEquals(listOf(other.id), library.followedShows().map { it.first.id })
    }

    private val dir: File = Files.createTempDirectory("vodnext").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun nextEpisodesShowsTheNextAiredEpisodeOfShowsWhoseLatestEpisodeIsOver() {
        val http = FakeHttp()
        http.answers[Cinemeta.metaUrl(Kind.SERIES, show.id)] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        val libStore = MemoryLibraryStore()
        val repo = DefaultVodRepository(
            http = http,
            tmdb = FakeTmdb(),
            tmdbKey = { "" },
            cache = JsonCache(File(dir, "vod")) { now },
            idStore = MemoryIdStore(),
            libraryStore = libStore,
            main = direct,
            io = direct,
            net = direct,
            lookups = direct,
            clock = { now },
        )
        val row = RowSpec("next", "Next Episodes", RowStyle.EPISODE, Source.NextEpisodes())
        fun cards(): List<Card> {
            var got: List<Card> = emptyList()
            repo.load(row) { c, _ -> got = c }
            return got
        }
        // The page stored the show's episodes once.
        repo.details(show) { _, _ -> }
        assertTrue(cards().isEmpty())
        // Half way through S1 E1: still Continue Watching, not Next Episodes.
        repo.recordProgress(show, EpisodeRef(1, 1), 10 * 60_000L, 24 * 60_000L)
        assertTrue(cards().isEmpty())
        // Watched to the end: S1 E2 is next.
        now += 1000
        RecordedLibrary(repo.library).recordPlayback(show, EpisodeRef(1, 1), 23 * 60_000L, 24 * 60_000L, null)
        val next = cards().single()
        assertEquals(show.id, next.title.id)
        assertEquals(EpisodeRef(1, 2), next.episode?.copy(name = null))
        assertEquals("S1 E2", next.caption)
        // Taken out of Continue Watching: no longer followed here.
        repo.removeFromContinueWatching(show.id)
        assertTrue(cards().isEmpty())
    }
}
