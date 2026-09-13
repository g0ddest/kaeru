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
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.player.FakeEpisodeSource
import app.kaeru.test.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
    private val streams = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock))
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
        assertEquals(listOf("AniLibria.TV", "Студийная банда"), vm.uiState.value.translations.map { it.translation.title })
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
        assertEquals(listOf("Студийная банда", "AniLibria.TV"), vm.uiState.value.translations.map { it.translation.title })
    }

    @Test
    fun `the chooser marks the dub this viewer keeps choosing, and only while none is remembered`() =
        runTest(main.dispatcher) {
            watchStates.seed(WatchState(1, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
            watchStates.seed(WatchState(2, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
            val vm = viewModel(FakeRepository(item))
            advanceUntilIdle()

            vm.loadTranslations()
            advanceUntilIdle()
            assertEquals(listOf("Студийная банда", "AniLibria.TV"), vm.uiState.value.translations.map { it.translation.title })
            assertEquals(listOf(true, false), vm.uiState.value.translations.map { it.oftenChosen })
        }

    @Test
    fun `picking a dub leaves the chooser marking it chosen and nothing else`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(1, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
        watchStates.seed(WatchState(2, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()
        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(listOf(true, false), vm.uiState.value.translations.map { it.oftenChosen })

        vm.pickTranslation(source.studioBanda)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.translations.none { it.oftenChosen })

        // Reopening re-reads the catalogue, because this anime now remembers a dub and the list it
        // was given was ranked for an anime that remembered none.
        vm.loadTranslations()
        advanceUntilIdle()
        assertEquals(2, source.translationCalls)
        assertEquals(listOf("Студийная банда", "AniLibria.TV"), vm.uiState.value.translations.map { it.translation.title })
        assertTrue(vm.uiState.value.translations.none { it.oftenChosen })
    }

    @Test
    fun `a dub that could not be saved leaves the list alone, so the retry still has it`() =
        runTest(main.dispatcher) {
            watchStates.seed(WatchState(1, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
            watchStates.seed(WatchState(2, 1, 0, 0, source.studioBanda.id, 1, Instant.EPOCH))
            val vm = viewModel(FakeRepository(item))
            advanceUntilIdle()
            vm.loadTranslations()
            advanceUntilIdle()

            watchStates.failSaveWith = IllegalStateException("disk")
            vm.pickTranslation(source.studioBanda)
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.translations.size)
            vm.loadTranslations()
            advanceUntilIdle()
            assertEquals(1, source.translationCalls)
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
    fun `a picked dub is remembered by name as well as by number`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()

        vm.pickTranslation(source.studioBanda)
        advanceUntilIdle()

        // The pill on this screen has no other way to name the dub without asking Kodik again.
        assertEquals(source.studioBanda.title, watchStates.saved.last().translationTitle)
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

    @Test
    fun `the chooser shows it is reading the catalogue while it reads it`() = runTest(main.dispatcher) {
        source.translationsGate = CompletableDeferred()
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()

        vm.loadTranslations()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loadingTranslations)

        source.translationsGate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loadingTranslations)
        assertEquals(2, vm.uiState.value.translations.size)
    }

    @Test
    fun `the dub control shows the write in flight`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeRepository(item))
        advanceUntilIdle()

        watchStates.block()
        vm.pickTranslation(source.anilibria)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.savingTranslation)

        watchStates.release()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.savingTranslation)
    }

    @Test
    fun `retrying a failed dub repeats the dub, not the load`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        watchStates.failSaveWith = IllegalStateException("disk")
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.pickTranslation(source.studioBanda)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)

        watchStates.failSaveWith = null
        repo.refreshed = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(source.studioBanda.id, watchStates.saved.last().translationId)
        assertNull("the load was not repeated", repo.refreshed)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `retrying anything else repeats the load`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = viewModel(repo)
        advanceUntilIdle()

        repo.refreshed = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(7, repo.refreshed)
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
