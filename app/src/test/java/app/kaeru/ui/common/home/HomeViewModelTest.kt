package app.kaeru.ui.common.home

import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.repository.DiscoverRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val summer = Season(SeasonKind.SUMMER, 2026)
    private val spring = Season(SeasonKind.SPRING, 2026)
    private val fall = Season(SeasonKind.FALL, 2026)

    private class FakeLibraryRepository : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
        var refreshResult: Result<Unit> = Result.success(Unit)
        var refreshCalls = 0
        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = MutableStateFlow(null)
        override fun observeAnime(id: Int): Flow<LibraryEntry?> =
            MutableStateFlow(entries.value.firstOrNull { it.anime.id == id })
        override suspend fun refresh(): Result<Unit> { refreshCalls++; return refreshResult }
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    private class FakeDiscoverRepository : DiscoverRepository {
        var now: Result<List<Anime>> = Result.success(emptyList())
        val bySeason = mutableMapOf<Season, Result<List<Anime>>>()
        var fallback: Result<List<Anime>> = Result.success(emptyList())
        /** Every read, in order, as «key» or «key!» when the caller forced it past the cache. */
        val reads = mutableListOf<String>()
        /** When set, a seasonal read waits on it, so a test can look at the screen mid-request. */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun popularNow(force: Boolean): Result<List<Anime>> {
            reads += if (force) "now!" else "now"
            return now
        }

        override suspend fun seasonal(season: Season, force: Boolean): Result<List<Anime>> {
            reads += season.apiValue + if (force) "!" else ""
            hold?.await()
            return bySeason[season] ?: fallback
        }
    }

    private fun anime(id: Int) = Anime(
        id, "Тайтл $id", "Title $id", null, emptyList(), AnimeStatus.ONGOING, 12, 5, null, 8.0, 2026, "MAPPA", null,
    )

    private fun viewModel(
        library: FakeLibraryRepository = FakeLibraryRepository(),
        discover: FakeDiscoverRepository = FakeDiscoverRepository(),
        prefs: FakePlaybackPreferences = FakePlaybackPreferences(),
    ) = HomeViewModel(library, discover, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), prefs)

    private fun entry(id: Int = 7) = LibraryEntry(
        anime = Anime(id, "Фрирен", "Sousou no Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", null),
        rate = UserRate(11, id, ListStatus.WATCHING, 20, now),
        watch = null,
    )

    @Test
    fun `cached room content is exposed before refresh completes`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = HomeViewModel(repo, FakeDiscoverRepository(), HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), FakePlaybackPreferences())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `failed refresh keeps cached feed and exposes retryable error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also {
            it.entries.value = listOf(entry())
            it.refreshResult = Result.failure(NetworkUnavailable(UnknownHostException("shikimori.io")))
        }
        val vm = HomeViewModel(repo, FakeDiscoverRepository(), HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), FakePlaybackPreferences())
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `the watched threshold reaches the screen from settings`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = HomeViewModel(repo, FakeDiscoverRepository(), HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), FakePlaybackPreferences(threshold = 0.95f))
        advanceUntilIdle()
        assertEquals(0.95f, vm.uiState.value.watchedThreshold, 0f)
    }

    @Test
    fun `manual refresh clears previous error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.refreshResult = Result.failure(HttpError(500)) }
        val vm = HomeViewModel(repo, FakeDiscoverRepository(), HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), FakePlaybackPreferences())
        advanceUntilIdle()
        assertEquals("Shikimori недоступен, попробуйте позже", vm.uiState.value.errorMessage)
        repo.refreshResult = Result.success(Unit)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage == null)
        assertEquals(2, repo.refreshCalls)
    }

    // --- discovery ---------------------------------------------------------------------------

    @Test
    fun `both discovery rows are read once the screen opens`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.now = Result.success(listOf(anime(1)))
            it.bySeason[summer] = Result.success(listOf(anime(2)))
        }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        val state = vm.uiState.value.discover
        assertEquals(summer, state.season)
        assertEquals(listOf(1), state.popularNow?.map { it.id })
        assertEquals(listOf(2), state.seasonal?.map { it.id })
        assertFalse(state.loadingNow)
        assertFalse(state.loadingSeasonal)
    }

    @Test
    fun `opening the screen reads the catalogue through its cache, not past it`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        viewModel(discover = discover)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026"), discover.reads)
    }

    @Test
    fun `the chips offer the season before, the one airing and the one coming`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf(spring, summer, fall), vm.uiState.value.discover.seasons)
    }

    @Test
    fun `a discovery row that could not be read is absent rather than wrong`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.now = Result.failure(NetworkUnavailable(UnknownHostException("shikimori.io")))
            it.fallback = Result.failure(HttpError(503))
        }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        assertNull(vm.uiState.value.discover.popularNow)
        assertNull(vm.uiState.value.discover.seasonal)
        assertFalse(vm.uiState.value.discover.loadingNow)
    }

    @Test
    fun `a discovery failure never becomes the screen's error`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.now = Result.failure(HttpError(503)) }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `choosing a season reads that season and shows it`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.bySeason[summer] = Result.success(listOf(anime(2)))
            it.bySeason[spring] = Result.success(listOf(anime(3)))
        }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        assertEquals(spring, vm.uiState.value.discover.season)
        assertEquals(listOf(3), vm.uiState.value.discover.seasonal?.map { it.id })
        assertEquals(listOf("now", "summer_2026", "spring_2026"), discover.reads)
    }

    @Test
    fun `a season already on the screen comes back without another read`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "spring_2026"), discover.reads)
        assertFalse(vm.uiState.value.discover.loadingSeasonal)
    }

    @Test
    fun `a season that failed is read again when it is chosen again`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.bySeason[spring] = Result.failure(HttpError(503)) }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "spring_2026", "spring_2026"), discover.reads)
    }

    @Test
    fun `pulling to refresh asks the catalogue again instead of repeating its answer`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "now!", "summer_2026!"), discover.reads)
    }

    @Test
    fun `a refresh forgets the other seasons so a later chip is fresh too`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        // The pull forces the season on screen; summer was read before it and must not survive.
        vm.refresh()
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        assertEquals(
            listOf("now", "summer_2026", "spring_2026", "now!", "spring_2026!", "summer_2026"),
            discover.reads,
        )
    }

    @Test
    fun `a pull leaves the season on screen in place until the new titles land`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.bySeason[summer] = Result.success(listOf(anime(2))) }
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        val request = CompletableDeferred<Unit>()
        discover.hold = request

        vm.refresh()
        advanceUntilIdle()

        // Mid-request: the row is still the row, not a hole where it used to be.
        assertEquals(listOf(2), vm.uiState.value.discover.seasonal?.map { it.id })
        assertTrue(vm.uiState.value.discover.loadingSeasonal)
        request.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2), vm.uiState.value.discover.seasonal?.map { it.id })
        assertFalse(vm.uiState.value.discover.loadingSeasonal)
    }

    @Test
    fun `pressing the chip already selected does nothing`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = viewModel(discover = discover)
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026"), discover.reads)
    }
}
