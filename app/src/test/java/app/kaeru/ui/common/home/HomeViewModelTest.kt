package app.kaeru.ui.common.home

import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.PrefetchTopCardStream
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.repository.DiscoverRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.ui.common.design.primaryAction
import app.kaeru.test.MainDispatcherRule
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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

    /** Kodik, only counting. Everything it is asked for it answers, so only the asking matters. */
    private class RecordingSource : EpisodeSourceProvider {
        val resolves = mutableListOf<Pair<Int, Int>>()
        private val track = Translation(11, "AniLibria.TV", TranslationKind.VOICE, 24)

        override suspend fun translations(shikimoriId: Int) = Result.success(listOf(track))

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?): Result<EpisodeStream> {
            resolves += shikimoriId to episode
            return Result.success(
                EpisodeStream(
                    shikimoriId, episode, translation ?: track,
                    mapOf(Quality.P720 to "https://cdn/$shikimoriId/$episode"),
                    Instant.parse("2026-09-12T12:00:00Z"),
                ),
            )
        }
    }

    private val source = RecordingSource()
    private val watchStates = FakeWatchStateRepository()

    /**
     * A title this device has played before, which is what a continue-watching card is. The
     * prefetch only prepares those: an anime with no remembered voice could never claim the
     * links, because the cache is keyed on it.
     */
    private fun remembering(animeId: Int = 7, episode: Int = 20) = watchStates.seed(
        WatchState(animeId, episode, 900_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now),
    )

    private fun prefetching(prefs: FakePlaybackPreferences): PrefetchTopCardStream {
        val clock = MutableClock(now)
        val cache = StreamPrefetchCache(clock)
        return PrefetchTopCardStream(
            ResolveEpisodeStream(source, watchStates, prefs, clock, cache),
            cache,
            watchStates,
            FakeDownloadRepository(),
        )
    }

    private fun viewModel(
        library: FakeLibraryRepository = FakeLibraryRepository(),
        discover: FakeDiscoverRepository = FakeDiscoverRepository(),
        prefs: FakePlaybackPreferences = FakePlaybackPreferences(),
    ) = HomeViewModel(
        library, discover, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC), prefs,
        prefetching(prefs), main.dispatcher,
    )

    /** The phone's home screen asks for the catalogue; nothing else does. */
    private fun TestScope.openedHome(
        library: FakeLibraryRepository = FakeLibraryRepository(),
        discover: FakeDiscoverRepository = FakeDiscoverRepository(),
    ): HomeViewModel = viewModel(library, discover).also {
        it.loadDiscover()
        advanceUntilIdle()
    }

    private fun HomeViewModel.discovered() = uiState.value.discover!!

    private fun entry(id: Int = 7) = LibraryEntry(
        anime = Anime(id, "Фрирен", "Sousou no Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", null),
        rate = UserRate(11, id, ListStatus.WATCHING, 20, now),
        watch = null,
    )

    /**
     * An ongoing show with ten episodes aired, five counted on Shikimori, and the sixth stopped
     * at [fraction] of the way through.
     *
     * The one fixture where the viewer's threshold changes the answer rather than the wording: at
     * 0.85 the sixth episode is behind them at a threshold of 0.8 and still in front of them at
     * 0.9, and the feed and the button have to agree about which.
     */
    private fun partway(fraction: Float = 0.85f) = LibraryEntry(
        anime = Anime(7, "Фрирен", "Sousou no Frieren", null, emptyList(), AnimeStatus.ONGOING, 24, 10, null, 9.1, 2023, "Madhouse", null),
        rate = UserRate(11, 7, ListStatus.WATCHING, 5, now),
        watch = null,
        progress = listOf(EpisodeProgress(7, 6, (fraction * 1_440_000).toLong(), 1_440_000, now)),
    )

    private fun library(vararg entries: LibraryEntry) =
        FakeLibraryRepository().also { it.entries.value = entries.toList() }

    @Test
    fun `cached room content is exposed before refresh completes`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = viewModel(repo)
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
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `the watched threshold reaches the screen from settings`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = viewModel(repo, prefs = FakePlaybackPreferences(threshold = 0.95f))
        advanceUntilIdle()
        assertEquals(0.95f, vm.uiState.value.watchedThreshold, 0f)
    }

    // --- the threshold the viewer chose decides the feed, not only the words over it -----------

    @Test
    fun `a lower threshold moves the feed on to the next episode`() = runTest(main.dispatcher) {
        // Eighty-five percent of the sixth is behind a viewer whose threshold is 0.8, so the show
        // is not something to continue — it is a show with a new episode waiting.
        val vm = viewModel(library(partway()), prefs = FakePlaybackPreferences(threshold = 0.8f))
        advanceUntilIdle()

        val top = vm.uiState.value.feed.top!!
        assertEquals(FeedKind.NEW_EPISODE, top.kind)
        assertEquals(7, top.episode)
        assertTrue(vm.uiState.value.feed.continueWatching.isEmpty())
    }

    @Test
    fun `the hero's label names the episode the hero starts`() = runTest(main.dispatcher) {
        // The button's words come from `primaryAction` at the viewer's threshold and the press
        // plays `feed.top.episode`. A feed built at some other threshold makes the two disagree,
        // and the disagreement is a wrong episode starting rather than a wrong word.
        val prefs = FakePlaybackPreferences(threshold = 0.8f)
        val vm = viewModel(library(partway()), prefs = prefs)
        advanceUntilIdle()

        val low = vm.uiState.value
        val lowTop = low.feed.top!!
        assertEquals("Продолжить 7 серию", primaryAction(lowTop.entry, low.watchedThreshold, now).label)
        assertEquals(7, lowTop.episode)

        // The same eighty-five percent is a place to come back to once the viewer asks for 0.9,
        // and both halves say six.
        prefs.watchedThreshold.value = 0.9f
        advanceUntilIdle()

        val high = vm.uiState.value
        val highTop = high.feed.top!!
        assertEquals(6, primaryAction(highTop.entry, high.watchedThreshold, now).episode)
        assertEquals(6, highTop.episode)
    }

    @Test
    fun `a title unfinished only by the viewer's own threshold keeps its place in the row`() =
        runTest(main.dispatcher) {
            // Ninety-two percent is finished at 0.9 and unfinished at 0.95. Built at 0.9, the row
            // drops the title the screen around it is still drawing a progress strip over.
            val vm = viewModel(library(partway(fraction = 0.92f)), prefs = FakePlaybackPreferences(threshold = 0.95f))
            advanceUntilIdle()

            assertEquals(listOf(6), vm.uiState.value.feed.continueWatching.map { it.episode })
        }

    @Test
    fun `the card prepared ahead of the press is the one the press will play`() =
        runTest(main.dispatcher) {
            // The prefetch resolves `feed.top.episode`; the button starts the same number. Prepare
            // the wrong one and the press pays the resolve it was supposed to have skipped.
            remembering(episode = 6)
            val vm = viewModel(library(partway()), prefs = FakePlaybackPreferences(threshold = 0.8f))
            advanceUntilIdle()

            vm.prefetchTopCard()
            advanceUntilIdle()

            // Anime 7, episode 7 — the number the button is about to name.
            assertEquals(listOf(7 to 7), source.resolves)
            assertEquals(7, vm.uiState.value.feed.top!!.episode)
        }

    @Test
    fun `manual refresh clears previous error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.refreshResult = Result.failure(HttpError(500)) }
        val vm = viewModel(repo)
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
    fun `a screen that never asks for the catalogue never pays for it`() = runTest(main.dispatcher) {
        // The television shares this view model and draws no catalogue rows. Four requests it does
        // not use would sit in the rate limiter ahead of the library sync it does.
        val discover = FakeDiscoverRepository()
        viewModel(discover = discover)
        advanceUntilIdle()
        assertTrue(discover.reads.isEmpty())
    }

    @Test
    fun `both discovery rows are read once the home screen asks`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.now = Result.success(listOf(anime(1)))
            it.bySeason[summer] = Result.success(listOf(anime(2)))
        }
        val vm = openedHome(discover = discover)
        val state = vm.discovered()
        assertEquals(summer, state.season)
        assertEquals(listOf(1), state.popularNow?.map { it.id })
        assertEquals(listOf(2), state.seasonal?.map { it.id })
        assertFalse(state.loadingNow)
        assertFalse(state.loadingSeasonal)
    }

    @Test
    fun `opening the screen reads the catalogue through its cache, not past it`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        openedHome(discover = discover)
        assertEquals(listOf("now", "summer_2026"), discover.reads)
    }

    @Test
    fun `coming back to the home screen does not read the catalogue again`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = openedHome(discover = discover)
        vm.loadDiscover()
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026"), discover.reads)
    }

    @Test
    fun `the chips offer the season before, the one airing and the one coming`() = runTest(main.dispatcher) {
        assertEquals(listOf(spring, summer, fall), openedHome().discovered().seasons)
    }

    @Test
    fun `a discovery row that could not be read is absent rather than wrong`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.now = Result.failure(NetworkUnavailable(UnknownHostException("shikimori.io")))
            it.fallback = Result.failure(HttpError(503))
        }
        val vm = openedHome(discover = discover)
        assertNull(vm.discovered().popularNow)
        assertNull(vm.discovered().seasonal)
        assertFalse(vm.discovered().loadingNow)
        // Nothing ever came back, so the switcher has never been useful and goes with the row.
        assertFalse(vm.discovered().anySeasonLoaded)
    }

    @Test
    fun `a discovery failure never becomes the screen's error`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.now = Result.failure(HttpError(503)) }
        assertNull(openedHome(discover = discover).uiState.value.errorMessage)
    }

    @Test
    fun `a season that answers, even with nothing, earns the switcher its place`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.fallback = Result.success(emptyList()) }
        val vm = openedHome(discover = discover)
        assertTrue(vm.discovered().anySeasonLoaded)
        assertEquals(emptyList<Anime>(), vm.discovered().seasonal)
    }

    @Test
    fun `choosing a season reads that season and shows it`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also {
            it.bySeason[summer] = Result.success(listOf(anime(2)))
            it.bySeason[spring] = Result.success(listOf(anime(3)))
        }
        val vm = openedHome(discover = discover)
        vm.selectSeason(spring)
        advanceUntilIdle()
        assertEquals(spring, vm.discovered().season)
        assertEquals(listOf(3), vm.discovered().seasonal?.map { it.id })
        assertEquals(listOf("now", "summer_2026", "spring_2026"), discover.reads)
    }

    @Test
    fun `a season already on the screen comes back without another read`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = openedHome(discover = discover)
        vm.selectSeason(spring)
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "spring_2026"), discover.reads)
        assertFalse(vm.discovered().loadingSeasonal)
    }

    @Test
    fun `a season that failed is read again when it is chosen again`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.bySeason[spring] = Result.failure(HttpError(503)) }
        val vm = openedHome(discover = discover)
        vm.selectSeason(spring)
        advanceUntilIdle()
        vm.selectSeason(summer)
        advanceUntilIdle()
        vm.selectSeason(spring)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "spring_2026", "spring_2026"), discover.reads)
    }

    @Test
    fun `a season that failed after another one worked keeps the switcher and can be retried`() =
        runTest(main.dispatcher) {
            val discover = FakeDiscoverRepository().also { it.bySeason[spring] = Result.failure(HttpError(503)) }
            val vm = openedHome(discover = discover)
            vm.selectSeason(spring)
            advanceUntilIdle()
            assertNull(vm.discovered().seasonal)
            assertTrue(vm.discovered().anySeasonLoaded)

            discover.bySeason[spring] = Result.success(listOf(anime(3)))
            vm.retrySeason()
            advanceUntilIdle()

            assertEquals(listOf(3), vm.discovered().seasonal?.map { it.id })
            assertEquals(listOf("now", "summer_2026", "spring_2026", "spring_2026"), discover.reads)
        }

    @Test
    fun `pulling to refresh asks the catalogue again instead of repeating its answer`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = openedHome(discover = discover)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026", "now!", "summer_2026!"), discover.reads)
    }

    @Test
    fun `a refresh forgets the other seasons so a later chip is fresh too`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = openedHome(discover = discover)
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
        val vm = openedHome(discover = discover)
        val request = CompletableDeferred<Unit>()
        discover.hold = request

        vm.refresh()
        advanceUntilIdle()

        // Mid-request: the row is still the row, not a hole where it used to be.
        assertEquals(listOf(2), vm.discovered().seasonal?.map { it.id })
        assertTrue(vm.discovered().loadingSeasonal)
        request.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2), vm.discovered().seasonal?.map { it.id })
        assertFalse(vm.discovered().loadingSeasonal)
    }

    @Test
    fun `a failed refresh keeps the popular row the viewer was reading`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.now = Result.success(listOf(anime(1))) }
        val vm = openedHome(discover = discover)

        discover.now = Result.failure(NetworkUnavailable(UnknownHostException("shikimori.io")))
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(1), vm.discovered().popularNow?.map { it.id })
        assertFalse(vm.discovered().loadingNow)
    }

    @Test
    fun `a failed refresh keeps the seasonal row the viewer was reading`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository().also { it.bySeason[summer] = Result.success(listOf(anime(2))) }
        val vm = openedHome(discover = discover)

        discover.bySeason[summer] = Result.failure(HttpError(503))
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(2), vm.discovered().seasonal?.map { it.id })
        assertFalse(vm.discovered().loadingSeasonal)
    }

    @Test
    fun `a first read that fails still leaves the season absent, so pressing it retries`() =
        runTest(main.dispatcher) {
            val discover = FakeDiscoverRepository().also { it.bySeason[spring] = Result.failure(HttpError(503)) }
            val vm = openedHome(discover = discover)
            vm.selectSeason(spring)
            advanceUntilIdle()
            assertNull(vm.discovered().seasonal)
        }

    @Test
    fun `pressing the chip already selected does nothing`() = runTest(main.dispatcher) {
        val discover = FakeDiscoverRepository()
        val vm = openedHome(discover = discover)
        vm.selectSeason(summer)
        advanceUntilIdle()
        assertEquals(listOf("now", "summer_2026"), discover.reads)
    }

    // --- preparing the top card ---------------------------------------------------------------

    @Test
    fun `the card at the top of the screen is resolved before it is pressed`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        remembering()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.prefetchTopCard()
        advanceUntilIdle()

        // Twenty of twenty-eight counted, twenty-four aired: the card offers the twenty-first.
        assertEquals(listOf(7 to 21), source.resolves)
    }

    @Test
    fun `a screen that renders again does not ask Kodik again`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        remembering()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.prefetchTopCard()
        advanceUntilIdle()
        vm.prefetchTopCard()
        vm.prefetchTopCard()
        advanceUntilIdle()

        assertEquals(1, source.resolves.size)
    }

    @Test
    fun `a card offering an episode that has not aired is not prepared`() = runTest(main.dispatcher) {
        val waiting = entry().let { it.copy(anime = it.anime.copy(episodesAired = 20)) }
        remembering()
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(waiting) }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.prefetchTopCard()
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
    }

    @Test
    fun `a title nobody has started yet is not prepared`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.prefetchTopCard()
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
    }

    @Test
    fun `an empty screen has nothing to prepare`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.prefetchTopCard()
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
    }
}
