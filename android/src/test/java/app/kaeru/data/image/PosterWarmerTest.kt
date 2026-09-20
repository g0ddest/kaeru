package app.kaeru.data.image

import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Posters for what is downloaded, fetched while there is still a network to fetch them with.
 *
 * The rule the whole thing rests on: a title counts as done only once its poster is actually on
 * disk. Ticking it off before the fetch made the one case this exists for — the network going away
 * while a queue was being set up — the one case it never retried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosterWarmerTest {
    private val track = Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12)
    private val downloads = FakeDownloadRepository()
    private val library = FakeLibrary()

    /** Every url asked for, in order, and whether the next answer is a success. */
    private val fetched = mutableListOf<String>()
    private var succeeds = true
    private val fetcher = PosterFetcher { url ->
        fetched += url
        succeeds
    }

    /**
     * Unconfined, because the thing under test is a collector that runs for the life of the
     * process: what matters is what it does as the rows change, not when a scheduler gets to it.
     */
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    private fun warmer() = PosterWarmer(downloads, library, fetcher).also { it.start(scope) }

    @After
    fun tearDown() = scope.cancel()

    private class FakeLibrary : LibraryRepository {
        private val cards = MutableStateFlow<Map<Int, Anime>>(emptyMap())

        fun put(anime: Anime) = cards.update { it + (anime.id to anime) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = cards.map { it[id] }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    private fun anime(id: Int, poster: String?) = Anime(
        id = id, nameRu = "Аниме $id", nameRomaji = "Anime $id", posterUrl = poster,
        screenshotUrls = emptyList(), status = AnimeStatus.ONGOING, episodes = 12, episodesAired = 12,
        nextEpisodeAt = null, score = null, year = 2026, studio = null, description = null,
    )

    private fun downloaded(animeId: Int, episode: Int) =
        downloads.downloaded(animeId, episode, track, "https://cdn/$animeId/$episode", at = Instant.EPOCH)

    @Test
    fun `a downloaded title's poster is fetched once`() = runTest {
        library.put(anime(1, "https://poster/1"))
        downloaded(1, 7)
        warmer()

        downloaded(1, 8)

        assertEquals(listOf("https://poster/1"), fetched)
    }

    @Test
    fun `a fetch that failed is tried again on the next change`() = runTest {
        library.put(anime(1, "https://poster/1"))
        succeeds = false
        downloaded(1, 7)
        warmer()
        assertEquals(1, fetched.size)

        succeeds = true
        downloaded(2, 1)

        assertEquals(listOf("https://poster/1", "https://poster/1"), fetched)
    }

    @Test
    fun `a title Room has never heard of has no poster to fetch`() = runTest {
        downloaded(404, 1)
        warmer()

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a title with no artwork asks for nothing`() = runTest {
        library.put(anime(1, null))
        downloaded(1, 7)
        warmer()

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `every downloaded title gets its own poster`() = runTest {
        library.put(anime(1, "https://poster/1"))
        library.put(anime(2, "https://poster/2"))
        downloaded(1, 7)
        downloaded(2, 3)
        warmer()

        assertEquals(setOf("https://poster/1", "https://poster/2"), fetched.toSet())
    }
}
