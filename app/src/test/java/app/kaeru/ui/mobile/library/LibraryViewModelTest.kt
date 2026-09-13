package app.kaeru.ui.mobile.library

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackPreferences
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun item(id: Int, title: String, status: ListStatus, updated: String) = LibraryEntry(
        Anime(id, title, title, null, emptyList(), AnimeStatus.RELEASED, 12, 12, null, null, 2026, null, null),
        UserRate(id.toLong(), id, status, id, Instant.parse(updated)),
        null,
    )

    private class FakeRepository(private val library: MutableStateFlow<List<LibraryEntry>>) : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = library
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = MutableStateFlow(null)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String): Result<List<Anime>> = Result.success(emptyList())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    @Test
    fun `selected status is filtered then sorted by recent activity`() {
        val items = listOf(
            item(1, "А", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Б", ListStatus.WATCHING, "2026-09-10T00:00:00Z"),
            item(3, "В", ListStatus.PLANNED, "2026-09-11T00:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.UPDATED).map { it.anime.id })
        assertEquals(listOf(1, 2), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }

    @Test
    fun `two titles updated in the same second keep a stable order rather than the list's`() {
        val same = "2026-09-10T00:00:00Z"
        val items = listOf(
            item(1, "Ящер", ListStatus.WATCHING, same),
            item(2, "Аист", ListStatus.WATCHING, same),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.UPDATED).map { it.anime.id })
        assertEquals(
            listOf(2, 1),
            selectLibrary(items.reversed(), ListStatus.WATCHING, LibrarySort.UPDATED).map { it.anime.id },
        )
    }

    @Test
    fun `yo sorts where a reader expects it and not after ya`() {
        val items = listOf(
            item(1, "Яблоко", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Ёлка", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(3, "Ели", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
        )
        assertEquals(listOf(3, 2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }

    @Test
    fun `case does not decide the order`() {
        val items = listOf(
            item(1, "фрирен", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Дандадан", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }

    @Test
    fun `latin names come before cyrillic ones instead of being scattered by code point`() {
        val items = listOf(
            item(1, "Ария", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Bocchi the Rock", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }

    @Test
    fun `the first list out of the database ends the loading state and fills the counts`() = runTest(main.dispatcher) {
        val library = MutableStateFlow<List<LibraryEntry>>(emptyList())
        val vm = LibraryViewModel(FakeRepository(library), FakePlaybackPreferences())
        assertTrue(vm.uiState.value.isLoading)
        library.value = listOf(
            item(1, "А", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Б", ListStatus.PLANNED, "2026-09-02T00:00:00Z"),
            item(3, "В", ListStatus.PLANNED, "2026-09-03T00:00:00Z"),
        )
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.counts[ListStatus.WATCHING])
        assertEquals(2, state.counts[ListStatus.PLANNED])
        assertEquals(listOf(1), state.items.map { it.anime.id })
    }

    @Test
    fun `switching a tab changes what is listed and leaves the counts alone`() = runTest(main.dispatcher) {
        val library = MutableStateFlow(
            listOf(
                item(1, "А", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
                item(2, "Б", ListStatus.PLANNED, "2026-09-02T00:00:00Z"),
            ),
        )
        val vm = LibraryViewModel(FakeRepository(library), FakePlaybackPreferences())
        advanceUntilIdle()
        vm.selectStatus(ListStatus.PLANNED)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf(2), state.items.map { it.anime.id })
        assertEquals(ListStatus.PLANNED, state.status)
        assertEquals(1, state.counts[ListStatus.WATCHING])
    }
}
