package app.kaeru.ui.mobile.search

import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val result = Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED, 28, 28, null, 9.1, 2023, null, null)

    private class FakeRepository(
        private val result: Anime,
        private val searchResult: Result<List<Anime>>? = null,
        private val statusResult: Result<Unit> = Result.success(Unit),
    ) : LibraryRepository {
        var query: String? = null
        var searches = 0
        var status: Pair<Int, ListStatus>? = null
        var statusCalls = 0
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
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
    }

    @Test
    fun `a failed search reports the cause and leaves no results behind`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, searchResult = Result.failure(NetworkUnavailable(java.io.IOException("offline"))))
        val vm = SearchViewModel(repo)
        vm.setQuery("Фрирен")
        vm.submit()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("Нет соединения. Проверьте интернет", state.errorMessage)
        assertTrue(state.results.isEmpty())
        assertEquals(SearchContent.Error("Нет соединения. Проверьте интернет"), searchContentState(state))
    }

    @Test
    fun `a failed add names the title it failed on and never blanks the results`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, statusResult = Result.failure(NetworkUnavailable(java.io.IOException("offline"))))
        val vm = SearchViewModel(repo)
        vm.setQuery("Фрирен")
        vm.submit()
        advanceUntilIdle()
        vm.addToPlanned(7)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(AddFailure(7, "Нет соединения. Проверьте интернет"), state.addFailure)
        assertNull(state.errorMessage)
        assertEquals(listOf(7), state.results.map { it.id })
        assertEquals(SearchContent.Results, searchContentState(state))
    }

    @Test
    fun `trying the add again clears the last failure and asks the repository once more`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result, statusResult = Result.failure(NetworkUnavailable(java.io.IOException("offline"))))
        val vm = SearchViewModel(repo)
        vm.addToPlanned(7)
        advanceUntilIdle()
        assertEquals(AddFailure(7, "Нет соединения. Проверьте интернет"), vm.uiState.value.addFailure)
        vm.addToPlanned(7)
        assertNull(vm.uiState.value.addFailure)
        assertEquals(7, vm.uiState.value.addingAnimeId)
        advanceUntilIdle()
        assertEquals(2, repo.statusCalls)
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
