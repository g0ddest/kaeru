package app.kaeru.ui.common.downloads

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

private const val MB = 1024L * 1024
private const val GB = 1024L * 1024 * 1024

/**
 * The «Загрузки» screen as values: what is on the device, grouped by title, and the four rules
 * that decide what goes on it next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")

    private val downloads = FakeDownloadRepository()
    private val settings = FakeSettingsStore()
    private val library = FakeLibrary()
    private val connectivity = FakeConnectivity()

    private class FakeLibrary : LibraryRepository {
        private val cards = MutableStateFlow<Map<Int, Anime>>(emptyMap())
        val refreshed = mutableListOf<Int>()

        fun put(anime: Anime) = cards.update { it + (anime.id to anime) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = cards.map { it[id] }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> {
            refreshed += id
            return Result.success(Unit)
        }
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    private fun anime(id: Int, title: String) = Anime(
        id = id, nameRu = title, nameRomaji = "Title $id", posterUrl = "https://poster/$id",
        screenshotUrls = emptyList(), status = AnimeStatus.ONGOING, episodes = 12, episodesAired = 12,
        nextEpisodeAt = null, score = null, year = 2026, studio = null, description = null,
    )

    private fun put(
        animeId: Int,
        episode: Int,
        state: DownloadState = DownloadState.COMPLETED,
        bytes: Long = 320 * MB,
        at: Instant = now,
    ) = downloads.put(
        EpisodeDownload(
            key = DownloadKey(animeId, episode, translationId = 7, quality = Quality.P720),
            state = state,
            bytes = bytes,
            progress = if (state == DownloadState.COMPLETED) 1f else 0.42f,
            failure = null,
            updatedAt = at,
        ),
    )

    private fun viewModel() = DownloadsViewModel(downloads, settings, library, connectivity)

    // --- grouping -------------------------------------------------------------------------------

    @Test
    fun `episodes are grouped under their title, in episode order, with the bytes they take`() = runTest {
        library.put(anime(1, "Фрирен"))
        put(1, 7, bytes = 320 * MB)
        put(1, 5, bytes = 300 * MB)
        val vm = viewModel()
        advanceUntilIdle()

        val title = vm.uiState.value.titles.single()
        assertEquals(1, title.animeId)
        assertEquals("Фрирен", title.title)
        assertEquals("https://poster/1", title.posterUrl)
        assertEquals(listOf(5, 7), title.episodes.map { it.episode })
        assertEquals(620 * MB, title.bytes)
    }

    @Test
    fun `the title whose download changed last is first`() = runTest {
        library.put(anime(1, "Фрирен"))
        library.put(anime(2, "Дандадан"))
        put(1, 1, at = now.minus(Duration.ofHours(2)))
        put(2, 1, at = now.minus(Duration.ofMinutes(5)))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(2, 1), vm.uiState.value.titles.map { it.animeId })
    }

    @Test
    fun `a title Room has never heard of is named by its number and asked for once`() = runTest {
        put(404, 1)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Тайтл №404", vm.uiState.value.titles.single().title)
        assertNull(vm.uiState.value.titles.single().posterUrl)
        assertEquals(listOf(404), library.refreshed)

        // A second round of rows must not become a second round of requests.
        put(404, 2)
        advanceUntilIdle()
        assertEquals(listOf(404), library.refreshed)
    }

    @Test
    fun `with no network there is nothing to ask Shikimori for`() = runTest {
        connectivity.goOffline()
        put(404, 1)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(library.refreshed.isEmpty())
        assertEquals("Тайтл №404", vm.uiState.value.titles.single().title)
    }

    /**
     * Each collection of the real `Connectivity` registers a `NetworkCallback` with the platform,
     * which caps a process at a hundred; a per-title collector put a phone with a hundred titles
     * at the edge of that, from the main thread. One collector, whatever is on the screen.
     */
    @Test
    fun `the network is collected once, however many titles are on the screen`() = runTest {
        (1..5).forEach { id ->
            library.put(anime(id, "Тайтл $id"))
            put(id, 1)
        }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(5, vm.uiState.value.titles.size)
        assertEquals(1, connectivity.state.subscriptionCount.value)
    }

    @Test
    fun `an unfinished download is still on the screen, because it is still taking space`() = runTest {
        library.put(anime(1, "Фрирен"))
        put(1, 3, state = DownloadState.DOWNLOADING, bytes = 100 * MB)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(DownloadState.DOWNLOADING, vm.uiState.value.titles.single().episodes.single().state)
    }

    @Test
    fun `the storage line comes from the engine rather than from the rows`() = runTest {
        downloads.setUsedBytes(3 * GB)
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = 5 * GB)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(3 * GB, vm.uiState.value.usedBytes)
        assertEquals(5 * GB, vm.uiState.value.limitBytes)
    }

    @Test
    fun `an empty screen is not a loading one`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.titles.isEmpty())
        assertFalse(vm.uiState.value.loading)
    }

    // --- the policy controls --------------------------------------------------------------------

    @Test
    fun `every policy control writes the whole policy back`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setQuality(Quality.P480)
        vm.setWifiOnly(false)
        vm.setDeleteWatched(true)
        vm.setLimit(null)
        advanceUntilIdle()

        assertEquals(
            DownloadPolicy(limitBytes = null, wifiOnly = false, deleteWatched = true, quality = Quality.P480),
            settings.downloadPolicy.value,
        )
        assertEquals(4, settings.writes.size)
        assertEquals(settings.downloadPolicy.value, vm.uiState.value.policy)
    }

    @Test
    fun `choosing what is already chosen writes nothing`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setQuality(DownloadPolicy.DEFAULT.quality)
        vm.setWifiOnly(DownloadPolicy.DEFAULT.wifiOnly)
        vm.setDeleteWatched(DownloadPolicy.DEFAULT.deleteWatched)
        vm.setLimit(DownloadPolicy.DEFAULT.limitBytes)
        advanceUntilIdle()

        assertTrue(settings.writes.isEmpty())
    }

    // --- taking things away ----------------------------------------------------------------------

    @Test
    fun `one episode, one title and everything are three different removals`() = runTest {
        library.put(anime(1, "Фрирен"))
        put(1, 1)
        put(1, 2)
        val vm = viewModel()
        advanceUntilIdle()

        vm.remove(1, 2)
        advanceUntilIdle()
        assertEquals(listOf(1 to 2), downloads.removed)
        assertEquals(listOf(1), vm.uiState.value.titles.single().episodes.map { it.episode })

        vm.removeTitle(1)
        advanceUntilIdle()
        assertEquals(listOf(1), downloads.removedTitles)
        assertTrue(vm.uiState.value.titles.isEmpty())

        vm.removeAll()
        advanceUntilIdle()
        assertEquals(1, downloads.removedEverything)
    }
}
