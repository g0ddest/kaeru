package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.player.FakeEpisodeSource
import app.kaeru.test.MainDispatcherRule
import java.io.IOException
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val prefs = FakePlaybackPreferences()
    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private val clock = Clock.fixed(Instant.parse("2026-09-13T20:00:00Z"), ZoneOffset.UTC)
    private val streams = ResolveEpisodeStream(source, watchStates, prefs, clock)
    private val item = LibraryEntry(
        Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", "Описание"),
        UserRate(1, 7, ListStatus.WATCHING, 20, Instant.EPOCH),
        null,
    )

    private class FakeRepository(initial: LibraryEntry?) : LibraryRepository {
        val entry = MutableStateFlow<LibraryEntry?>(initial)
        var refreshed: Int? = null
        var statusChange: Pair<Int, ListStatus>? = null
        val episodeWrites = mutableListOf<Pair<Int, Int>>()
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(listOfNotNull(entry.value))
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entry
        val details = MutableStateFlow<Anime?>(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = details
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> { refreshed = id; return Result.success(Unit) }
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusChange = animeId to status
            val known = entry.value
            entry.value = if (known != null) {
                known.copy(rate = known.rate.copy(status = status))
            } else {
                // Shikimori creates the rate on first write, and the next read sees it.
                details.value?.let { anime ->
                    LibraryEntry(anime, UserRate(2, animeId, status, 0, Instant.EPOCH), null)
                }
            }
            return Result.success(Unit)
        }
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> {
            episodeWrites += animeId to episodes
            val known = entry.value ?: return Result.success(Unit)
            entry.value = known.copy(rate = known.rate.copy(episodes = episodes))
            return Result.success(Unit)
        }
    }

    private fun viewModel(repo: FakeRepository) = DetailsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("animeId" to 7)),
        repository = repo,
        prefs = prefs,
        streams = streams,
        watchStates = watchStates,
        markEpisodeWatched = MarkEpisodeWatched(repo, watchStates, clock),
        clock = clock,
    )

    @Test
    fun `loads requested anime and changes list status`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(7, repo.refreshed)
        assertEquals("Фрирен", vm.uiState.value.entry?.anime?.title)
        vm.setStatus(ListStatus.PLANNED)
        advanceUntilIdle()
        assertEquals(7 to ListStatus.PLANNED, repo.statusChange)
        assertFalse(vm.uiState.value.updatingStatus)
    }

    @Test
    fun `anime outside the list is shown from cached details without an entry`() = runTest(main.dispatcher) {
        val repo = FakeRepository(null)
        repo.details.value = item.anime
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.entry)
        assertEquals("Фрирен", vm.uiState.value.anime?.title)
    }

    // --- the dub chooser -------------------------------------------------------------------------

    @Test
    fun `the dub list is asked for once, the first time the chooser is opened`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.translations.isEmpty())

        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(listOf("AniLibria.TV", "Студийная банда"), vm.uiState.value.translations.map { it.title })
        assertFalse(vm.uiState.value.loadingTranslations)
        assertEquals(1, source.translationCalls)

        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(1, source.translationCalls)
    }

    @Test
    fun `the dub the anime was last played with is offered first`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(7, 21, 700_000, 1_400_000, source.studioBanda.id, 2, Instant.EPOCH))
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()
        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(listOf("Студийная банда", "AniLibria.TV"), vm.uiState.value.translations.map { it.title })
    }

    @Test
    fun `a dub list that could not be read says why and can be asked for again`() = runTest(main.dispatcher) {
        source.translationsFailure = NetworkUnavailable(IOException("offline"))
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()

        vm.loadTranslations()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.translations.isEmpty())
        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.translationsError)

        source.translationsFailure = null
        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.translations.size)
        assertNull(vm.uiState.value.translationsError)
    }

    @Test
    fun `picking a dub keeps the episode and the position already remembered`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(7, 21, 700_000, 1_400_000, source.anilibria.id, 1, Instant.EPOCH))
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()

        vm.pickTranslation(source.studioBanda.copy(season = 2))
        advanceUntilIdle()
        val saved = watchStates.saved.last()
        assertEquals(21, saved.episode)
        assertEquals(700_000, saved.positionMs)
        assertEquals(1_400_000, saved.durationMs)
        assertEquals(source.studioBanda.id, saved.translationId)
        assertEquals(2, saved.kodikSeason)
    }

    @Test
    fun `picking a dub before anything was played starts a row at the episode the button offers`() =
        runTest(main.dispatcher) {
            val vm = viewModel(FakeRepository(item))
            advanceUntilIdle()

            vm.pickTranslation(source.anilibria)
            advanceUntilIdle()
            val saved = watchStates.saved.last()
            assertEquals(21, saved.episode)
            assertEquals(0, saved.positionMs)
            assertEquals(0, saved.durationMs)
            assertEquals(source.anilibria.id, saved.translationId)
        }

    @Test
    fun `a dub that could not be remembered leaves a message rather than failing silently`() =
        runTest(main.dispatcher) {
            watchStates.failSaveWith = IllegalStateException("disk")
            val vm = viewModel(FakeRepository(item))
            advanceUntilIdle()

            vm.pickTranslation(source.anilibria)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)
        }

    // --- marking an episode watched --------------------------------------------------------------

    @Test
    fun `marking an episode watched raises the count Shikimori holds`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.markWatched(21)
        advanceUntilIdle()
        assertEquals(listOf(7 to 21), repo.episodeWrites)
        assertFalse(vm.uiState.value.updatingStatus)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `marking an episode watched on an anime outside the list puts it in «Смотрю» first`() =
        runTest(main.dispatcher) {
            val repo = FakeRepository(null)
            repo.details.value = item.anime
            val vm = viewModel(repo)
            advanceUntilIdle()

            vm.markWatched(1)
            advanceUntilIdle()
            assertEquals(7 to ListStatus.WATCHING, repo.statusChange)
            assertEquals(listOf(7 to 1), repo.episodeWrites)
        }

    @Test
    fun `an episode already counted is not written again`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.markWatched(12)
        advanceUntilIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), repo.episodeWrites)
    }
}
