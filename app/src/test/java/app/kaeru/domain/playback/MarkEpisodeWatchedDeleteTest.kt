package app.kaeru.domain.playback

import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
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

    private val mark = MarkEpisodeWatched(library, watchStates, clock, downloads, settings)

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
}
