package app.kaeru.ui.mobile.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Anime> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val searching: Boolean = false,
    val errorMessage: String? = null,
    val addingAnimeId: Int? = null,
    /** Ids already in the user's list, so results can show «В списке» instead of «В планы». */
    val libraryIds: Set<Int> = emptySet(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutable

    init {
        viewModelScope.launch {
            repository.observeLibrary().collect { entries -> mutable.update { it.copy(libraryIds = entries.map { e -> e.anime.id }.toSet()) } }
        }
    }

    fun setQuery(value: String) = mutable.update { it.copy(query = value) }
    fun useRecent(value: String) { setQuery(value); submit() }

    fun submit() {
        val query = mutable.value.query.trim()
        if (query.length < 2 || mutable.value.searching) return
        viewModelScope.launch {
            mutable.update { it.copy(query = query, searching = true, errorMessage = null) }
            val result = repository.search(query)
            mutable.update { state ->
                state.copy(
                    results = result.getOrDefault(emptyList()),
                    recentQueries = (listOf(query) + state.recentQueries.filterNot { it == query }).take(5),
                    searching = false,
                    errorMessage = result.errorMessageOrNull(),
                )
            }
        }
    }

    fun addToPlanned(animeId: Int) {
        viewModelScope.launch {
            mutable.update { it.copy(addingAnimeId = animeId, errorMessage = null) }
            val result = repository.setStatus(animeId, ListStatus.PLANNED)
            mutable.update { it.copy(addingAnimeId = null, errorMessage = result.errorMessageOrNull()) }
        }
    }
}
