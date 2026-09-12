package app.kaeru.ui.mobile.search

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
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val result = Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED, 28, 28, null, 9.1, 2023, null, null)

    private class FakeRepository(private val result: Anime) : LibraryRepository {
        var query: String? = null
        var status: Pair<Int, ListStatus>? = null
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = MutableStateFlow(null)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String): Result<List<Anime>> { this.query = query; return Result.success(listOf(result)) }
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> { this.status = animeId to status; return Result.success(Unit) }
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
}
