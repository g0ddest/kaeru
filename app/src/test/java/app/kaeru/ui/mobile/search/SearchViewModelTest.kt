package app.kaeru.ui.mobile.search

import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
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
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val result = Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED, 28, 28, null, 9.1, 2023, null, null)

    private fun offline() = Result.failure<Nothing>(NetworkUnavailable(IOException("offline")))
    private val offlineMessage = "Нет соединения. Проверьте интернет"

    private fun entry(anime: Anime) = LibraryEntry(
        anime,
        UserRate(anime.id.toLong(), anime.id, ListStatus.PLANNED, 0, Instant.parse("2026-09-13T20:00:00Z")),
        null,
    )

    private class FakeRepository(
        private val result: Anime,
        private val searchResult: Result<List<Anime>>? = null,
        private val statusResult: Result<Unit> = Result.success(Unit),
        val library: MutableStateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList()),
    ) : LibraryRepository {
        var query: String? = null
        var searches = 0
        var status: Pair<Int, ListStatus>? = null
        var statusCalls = 0
        override fun observeLibrary(): Flow<List<LibraryEntry>> = library
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = MutableStateFlow(null)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String): Result<List<Anime>> {
            this.query = query
            searches++
            return searchResult ?: Result.success(listOf(result))
        }
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            this.status = animeId to status
            statusCalls++
            return statusResult
        }
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    @Test
    fun `submit trims query remembers it and add planned delegates to repository`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.setQuery("  Фрирен  ")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Фрирен", repo.query)
        assertEquals(listOf(7), vm.uiState.value.results.map { it.id })
        assertEquals(listOf("Фрирен"), vm.uiState.value.recentQueries)
        assertTrue(vm.uiState.value.hasSearched)
        vm.addToPlanned(7)
        advanceUntilIdle()
        assertEquals(7 to ListStatus.PLANNED, repo.status)
        assertFalse(vm.uiState.value.searching)
    }

    @Test
    fun `a query too short to mean anything is never sent`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.setQuery("ф")
        vm.submit()
        advanceUntilIdle()
        assertEquals(0, repo.searches)
        assertFalse(vm.uiState.value.hasSearched)
    }

    @Test
    fun `a failed search reports the cause and leaves no results behind`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, searchResult = offline())
        val vm = SearchViewModel(repo)
        vm.setQuery("Фрирен")
        vm.submit()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(offlineMessage, state.errorMessage)
        assertTrue(state.results.isEmpty())
        assertEquals(SearchContent.Error(offlineMessage), searchContentState(state))
    }

    /**
     * «Повторить» used to go through `submit()`, which reads the field. Clearing the field left the
     * error screen up with a button that hit the minimum-length guard and returned in silence.
     */
    @Test
    fun `retry re-sends the last query even when the field has been cleared`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, searchResult = offline())
        val vm = SearchViewModel(repo)
        vm.setQuery("Фрирен")
        vm.submit()
        advanceUntilIdle()
        vm.setQuery("")
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, repo.searches)
        assertEquals("Фрирен", repo.query)
        // What is being searched is what the field shows.
        assertEquals("Фрирен", vm.uiState.value.query)
    }

    @Test
    fun `retry before anything has been searched does nothing`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.retry()
        advanceUntilIdle()
        assertEquals(0, repo.searches)
    }

    @Test
    fun `a failed add names the title it failed on and never blanks the results`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, statusResult = offline())
        val vm = SearchViewModel(repo)
        vm.setQuery("Фрирен")
        vm.submit()
        advanceUntilIdle()
        vm.addToPlanned(7)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(7, state.addFailure?.animeId)
        assertEquals(offlineMessage, state.addFailure?.message)
        assertNull(state.errorMessage)
        assertEquals(listOf(7), state.results.map { it.id })
        assertEquals(SearchContent.Results, searchContentState(state))
    }

    /**
     * Two failures carrying the same words are two failures. The snackbar keys on the event, so the
     * number has to move even when the message does not.
     */
    @Test
    fun `each failure gets a number of its own`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, statusResult = offline())
        val vm = SearchViewModel(repo)
        vm.addToPlanned(7)
        advanceUntilIdle()
        val first = vm.uiState.value.addFailure!!
        vm.addToPlanned(8)
        advanceUntilIdle()
        val second = vm.uiState.value.addFailure!!
        assertEquals(first.message, second.message)
        assertTrue("$first then $second", second.event > first.event)
    }

    @Test
    fun `trying the add again clears the last failure and asks the repository once more`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, statusResult = offline())
        val vm = SearchViewModel(repo)
        vm.addToPlanned(7)
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.addFailure?.animeId)
        vm.addToPlanned(7)
        assertNull(vm.uiState.value.addFailure)
        assertEquals(7, vm.uiState.value.addingAnimeId)
        advanceUntilIdle()
        assertEquals(2, repo.statusCalls)
    }

    /**
     * The write returning is not the title being in the list. Clearing the pending mark there put
     * «В планы» back under a card for a frame or two, inviting a second press of a write that had
     * just succeeded.
     */
    @Test
    fun `a successful add stays pending until the list actually holds the title`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.addToPlanned(7)
        advanceUntilIdle()
        val pending = vm.uiState.value
        assertEquals(7, pending.addingAnimeId)
        assertEquals(AddAction("Добавляем…", enabled = false), addAction(pending.libraryIds, pending.addingAnimeId, 7))

        repo.library.value = listOf(entry(result))
        advanceUntilIdle()
        val done = vm.uiState.value
        assertNull(done.addingAnimeId)
        assertEquals(AddAction("В списке", enabled = false), addAction(done.libraryIds, done.addingAnimeId, 7))
    }

    @Test
    fun `a library emission without the pending title leaves it pending`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.addToPlanned(7)
        advanceUntilIdle()
        repo.library.value = listOf(entry(result.copy(id = 42)))
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.addingAnimeId)
    }

    @Test
    fun `a recent query is searched again without being retyped`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.useRecent("Дандадан")
        advanceUntilIdle()
        assertEquals("Дандадан", repo.query)
        assertEquals("Дандадан", vm.uiState.value.query)
    }
}
