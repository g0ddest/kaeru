package app.kaeru.ui.common.home

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.feed.HomeFeedBuilder
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

    private fun entry(id: Int = 7) = LibraryEntry(
        anime = Anime(id, "Фрирен", "Sousou no Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", null),
        rate = UserRate(11, id, ListStatus.WATCHING, 20, now),
        watch = null,
    )

    @Test
    fun `cached room content is exposed before refresh completes`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
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
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `manual refresh clears previous error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.refreshResult = Result.failure(HttpError(500)) }
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals("Shikimori недоступен, попробуйте позже", vm.uiState.value.errorMessage)
        repo.refreshResult = Result.success(Unit)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage == null)
        assertEquals(2, repo.refreshCalls)
    }
}
