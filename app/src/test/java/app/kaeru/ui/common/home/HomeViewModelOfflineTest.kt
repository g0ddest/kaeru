package app.kaeru.ui.common.home

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.discover.Season
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.PrefetchTopCardStream
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.repository.DiscoverRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.test.MainDispatcherRule
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The home screen with no network.
 *
 * Three things change and nothing else does: the strip appears, the catalogue rows go away — they
 * are the one part of this screen that cannot be answered from the device — and a refresh that
 * could not reach Shikimori says nothing, because «нет сети» is already on screen in one line and
 * a red bar under it would be the same news twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOfflineTest {
    @get:Rule val main = MainDispatcherRule()
    private val now: Instant = Instant.parse("2026-09-12T12:00:00Z")

    private class FakeLibraryRepository : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
        var refreshResult: Result<Unit> = Result.success(Unit)
        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = MutableStateFlow(null)
        override fun observeAnime(id: Int): Flow<LibraryEntry?> =
            MutableStateFlow(entries.value.firstOrNull { it.anime.id == id })
        override suspend fun refresh(): Result<Unit> = refreshResult
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    private class FakeDiscoverRepository : DiscoverRepository {
        override suspend fun popularNow(force: Boolean) = Result.success(listOf(anime(50)))
        override suspend fun seasonal(season: Season, force: Boolean) = Result.success(listOf(anime(51)))
    }

    private class SilentSource : EpisodeSourceProvider {
        private val track = Translation(11, "AniLibria.TV", TranslationKind.VOICE, 24)
        override suspend fun translations(shikimoriId: Int) = Result.success(listOf(track))
        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?) =
            Result.success(
                EpisodeStream(
                    shikimoriId, episode, translation ?: track,
                    mapOf(Quality.P720 to "https://cdn/$shikimoriId/$episode"),
                    Instant.parse("2026-09-12T12:00:00Z"),
                ),
            )
    }

    private val library = FakeLibraryRepository()
    private val downloads = FakeDownloadRepository()
    private val connectivity = FakeConnectivity()

    private fun viewModel(): HomeViewModel {
        val prefs = FakePlaybackPreferences()
        val clock = MutableClock(now)
        val cache = StreamPrefetchCache(clock)
        val watchStates = FakeWatchStateRepository()
        val prefetch = PrefetchTopCardStream(
            ResolveEpisodeStream(SilentSource(), watchStates, prefs, clock, cache), cache, watchStates,
        )
        return HomeViewModel(
            library, FakeDiscoverRepository(), HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC),
            prefs, prefetch, downloads, connectivity, main.dispatcher,
        )
    }

    private fun entry(id: Int = 7, watched: Int = 3) = LibraryEntry(
        anime = anime(id),
        rate = UserRate(11, id, ListStatus.WATCHING, watched, now),
        watch = null,
    )

    @Test
    fun `a network that is there is not mentioned`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun `losing the network says so`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        connectivity.goOffline()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.offline)
        connectivity.goOnline()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun `the catalogue is hidden with no network and comes back with one`() = runTest {
        library.entries.value = listOf(entry())
        val vm = viewModel()
        vm.loadDiscover()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.discover)

        connectivity.goOffline()
        advanceUntilIdle()
        assertNull(vm.uiState.value.discover)

        connectivity.goOnline()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.discover)
    }

    @Test
    fun `a refresh with no network is a strip rather than an error`() = runTest {
        library.entries.value = listOf(entry())
        library.refreshResult = Result.failure(UnknownHostException("shikimori.one"))
        connectivity.goOffline()
        val vm = viewModel()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.offline)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `a refresh that fails with a network still says what went wrong`() = runTest {
        library.entries.value = listOf(entry())
        library.refreshResult = Result.failure(UnknownHostException("shikimori.one"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `finished downloads reach the feed`() = runTest {
        library.entries.value = listOf(entry(watched = 3))
        downloads.downloaded(7, 4, Translation(11, "AniLibria.TV", TranslationKind.VOICE, 24), "https://cdn/7/4")
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(4), vm.uiState.value.feed.downloaded.map { it.episode })
        assertEquals(FeedKind.DOWNLOADED, vm.uiState.value.feed.downloaded.single().kind)
    }
}

private fun anime(id: Int) = Anime(
    id, "Тайтл $id", "Title $id", null, emptyList(), AnimeStatus.ONGOING, 12, 5, null, 8.0, 2026, "MAPPA", null,
)
