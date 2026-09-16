package app.kaeru.domain.playback

import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.DownloadedEpisode
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.download.FakeDeferredRemovals
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MutableClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * «Удалять просмотренные»: an episode counted on Shikimori gives its space back.
 *
 * Off by default and only ever acting on a download that has finished — a queue the viewer set up
 * for tonight is not something a mark should quietly cancel.
 */
class MarkEpisodeWatchedDeleteTest {
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val library = FakeLibrary()
    private val watchStates = FakeWatchStateRepository()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeSettingsStore()
    private val track = Translation(7, "AniLibria.TV", TranslationKind.VOICE, 24)

    private val owed = FakeDeferredRemovals()
    private val deleteWatchedDownloads = DeferredDownloadRemoval(downloads, settings, owed)
    private val mark = MarkEpisodeWatched(library, watchStates, clock, deleteWatchedDownloads)

    private class FakeLibrary : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        var episodesResult: Result<Unit> = Result.success(Unit)

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            entries.update { current ->
                val existing = current[animeId] ?: return@update current
                current + (animeId to existing.copy(rate = existing.rate.copy(status = status)))
            }
            return Result.success(Unit)
        }
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> {
            if (episodesResult.isFailure) return episodesResult
            entries.update { current ->
                val existing = current[animeId] ?: return@update current
                current + (animeId to existing.copy(rate = existing.rate.copy(episodes = episodes)))
            }
            return Result.success(Unit)
        }
    }

    private fun anime(id: Int = 100) = Anime(
        id = id, nameRu = "Имя", nameRomaji = "Name", posterUrl = null, screenshotUrls = emptyList(),
        status = AnimeStatus.RELEASED, episodes = 12, episodesAired = 12, nextEpisodeAt = null,
        score = null, year = null, studio = null, description = null,
    )

    private fun seed(watched: Int = 3) =
        library.put(LibraryEntry(anime(), UserRate(1, 100, ListStatus.WATCHING, watched, now), null))

    private fun deleteWatched(on: Boolean) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = on)
    }

    @Test
    fun `with the setting on, a marked episode gives its space back`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        assertTrue(mark(100, 4).isSuccess)

        assertEquals(listOf(100 to 4), downloads.removed)
    }

    @Test
    fun `with the setting off, the episode stays on the device`() = runTest {
        seed()
        deleteWatched(false)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        assertTrue(mark(100, 4).isSuccess)

        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun `nothing downloaded is nothing to remove`() = runTest {
        seed()
        deleteWatched(true)

        assertTrue(mark(100, 4).isSuccess)

        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun `a download still running is not cancelled by a mark`() = runTest {
        seed()
        deleteWatched(true)
        downloads.put(
            EpisodeDownload(
                key = DownloadKey(100, 4, translationId = 7, quality = Quality.P720),
                state = DownloadState.DOWNLOADING,
                bytes = 100L * 1024 * 1024,
                progress = 0.4f,
                failure = null,
                updatedAt = now,
            ),
        )

        assertTrue(mark(100, 4).isSuccess)

        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun `a mark that failed leaves the download alone`() = runTest {
        seed()
        deleteWatched(true)
        library.episodesResult = Result.failure(HttpError(500))
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        assertTrue(mark(100, 4).isFailure)

        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun `an episode Shikimori had already counted is not deleted from under the viewer`() = runTest {
        seed(watched = 4)
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        assertTrue(mark(100, 4).isSuccess)

        assertTrue(downloads.removed.isEmpty())
    }

    /**
     * The mark that triggers this is raised by the player at nine tenths of the episode, while the
     * file is still under the engine. Deleting it there tears the segments out of the cache the
     * player is reading, and offline — the case the download exists for — there is nothing behind
     * the dead Kodik address it falls through to.
     */
    @Test
    fun `the episode being played is not deleted out from under the player`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)

        assertTrue(mark(100, 4).isSuccess)

        assertTrue(downloads.removed.isEmpty())
        assertNotNull(downloads.completed(100, 4))
    }

    @Test
    fun `and goes the moment playback moves on to the next one`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)
        assertTrue(mark(100, 4).isSuccess)

        deleteWatchedDownloads.nowPlaying(100, 5)

        assertEquals(listOf(100 to 4), downloads.removed)
    }

    /**
     * `remove` is a plain `startService`, which Android refuses to a process the viewer cannot
     * see. A promise `keep()` tears up over a removal that never reached the engine is a promise
     * nobody keeps: the file stays exactly where it was, with nothing left to ask for it again.
     */
    @Test
    fun `a removal the platform refuses keeps the promise for the next sweep`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)
        assertTrue(mark(100, 4).isSuccess)
        downloads.refuseRemovals = true

        deleteWatchedDownloads.nowPlaying(100, 5)

        assertEquals(listOf(100 to 4), downloads.removed)
        assertEquals(setOf(DownloadedEpisode(100, 4)), owed.pending())
    }

    @Test
    fun `or when the player stops altogether`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)
        assertTrue(mark(100, 4).isSuccess)

        deleteWatchedDownloads.nowPlaying(null, null)

        assertEquals(listOf(100 to 4), downloads.removed)
    }

    @Test
    fun `an episode nobody is playing still goes at once`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 7)

        assertTrue(mark(100, 4).isSuccess)

        assertEquals(listOf(100 to 4), downloads.removed)
    }

    /** A process that died before playback moved on: the next start clears what it owed. */
    @Test
    fun `the sweep clears what a previous run promised`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)
        assertTrue(mark(100, 4).isSuccess)
        assertTrue("deferred, so nothing has gone yet", downloads.removed.isEmpty())

        // The process died on the credits. Nothing in memory survives; the promise does.
        nextLaunch().sweep()

        assertEquals(listOf(100 to 4), downloads.removed)
        assertTrue(owed.pending().isEmpty())
    }

    /**
     * The regression this set exists to prevent. Downloading an episode you have already watched
     * is something the app offers on purpose — the «Скачать…» sheet gives them their own block —
     * and a sweep that reasoned from Shikimori's count instead deleted every one of them on every
     * cold start, with no mark and no playback anywhere in the story.
     */
    @Test
    fun `a watched episode downloaded on purpose survives the sweep`() = runTest {
        seed(watched = 4)
        deleteWatched(true)
        downloads.downloaded(100, 3, track, "https://cdn/100/3")
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        nextLaunch().sweep()

        assertTrue(downloads.removed.isEmpty())
        assertNotNull(downloads.completed(100, 4))
    }

    @Test
    fun `the sweep does nothing with the setting off`() = runTest {
        seed(watched = 4)
        deleteWatched(false)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        owed.seed(DownloadedEpisode(100, 4))

        deleteWatchedDownloads.sweep()

        assertTrue(downloads.removed.isEmpty())
    }

    /** Turned off between the mark and the restart: the viewer has decided they want the episode. */
    @Test
    fun `a promise the setting outlived is dropped rather than kept`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")
        deleteWatchedDownloads.nowPlaying(100, 4)
        assertTrue(mark(100, 4).isSuccess)
        deleteWatched(false)

        nextLaunch().sweep()
        deleteWatched(true)
        nextLaunch().sweep()

        assertTrue(downloads.removed.isEmpty())
        assertTrue(owed.pending().isEmpty())
    }

    @Test
    fun `an episode deleted the ordinary way leaves no promise behind`() = runTest {
        seed()
        deleteWatched(true)
        downloads.downloaded(100, 4, track, "https://cdn/100/4")

        assertTrue(mark(100, 4).isSuccess)

        assertEquals(listOf(100 to 4), downloads.removed)
        assertTrue(owed.pending().isEmpty())
    }

    /** The same collaborator a new process would build, over the promises the last one left. */
    private fun nextLaunch() = DeferredDownloadRemoval(downloads, settings, owed)
}
