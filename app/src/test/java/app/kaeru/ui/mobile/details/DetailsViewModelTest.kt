package app.kaeru.ui.mobile.details

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import app.kaeru.data.library.AppPreferences
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope by lazy { TestScope(main.dispatcher) }
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
    }

    @After
    fun tearDown() = storeScope.cancel()
    private val item = LibraryEntry(
        Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", "Описание"),
        UserRate(1, 7, ListStatus.WATCHING, 20, Instant.EPOCH),
        null,
    )

    private class FakeRepository(initial: LibraryEntry?) : LibraryRepository {
        val entry = MutableStateFlow<LibraryEntry?>(initial)
        var refreshed: Int? = null
        var statusChange: Pair<Int, ListStatus>? = null
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(listOfNotNull(entry.value))
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entry
        val details = MutableStateFlow<Anime?>(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = details
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> { refreshed = id; return Result.success(Unit) }
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusChange = animeId to status
            entry.value = entry.value?.copy(rate = entry.value!!.rate.copy(status = status))
            return Result.success(Unit)
        }
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    @Test
    fun `loads requested anime and changes list status`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = DetailsViewModel(SavedStateHandle(mapOf("animeId" to 7)), repo, prefs)
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
        val vm = DetailsViewModel(SavedStateHandle(mapOf("animeId" to 7)), repo, prefs)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.entry)
        assertEquals("Фрирен", vm.uiState.value.anime?.title)
    }
}
