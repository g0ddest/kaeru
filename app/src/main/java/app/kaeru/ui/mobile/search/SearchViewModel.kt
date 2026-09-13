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

/** How many past queries are kept, which is about one screenful of chips. */
private const val RECENT_LIMIT = 5

/** Below this a query matches half the catalogue, so it is not worth a round trip. */
private const val MIN_QUERY = 2

/**
 * A write that did not go through, and the title it was about.
 *
 * The id travels with the message so the retry goes to the same title: without it a snackbar could
 * only offer «Повторить» for whatever the viewer pressed most recently, which after two failures
 * is a button that lies about what it will do.
 */
data class AddFailure(val animeId: Int, val message: String)

data class SearchUiState(
    val query: String = "",
    val results: List<Anime> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val searching: Boolean = false,
    /** The search itself failed. This one owns the screen; see [AddFailure] for the one that does not. */
    val errorMessage: String? = null,
    val addingAnimeId: Int? = null,
    /** A failed write over results that are still on screen and still worth acting on. */
    val addFailure: AddFailure? = null,
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
        if (query.length < MIN_QUERY || mutable.value.searching) return
        // Marked before the coroutine starts, so the guard above actually catches a double press
        // and the field goes quiet on the same frame as the key.
        mutable.update { it.copy(query = query, searching = true, errorMessage = null) }
        viewModelScope.launch {
            val result = repository.search(query)
            mutable.update { state ->
                state.copy(
                    results = result.getOrDefault(emptyList()),
                    // Remembered even when the search failed: the query was still typed, and the
                    // chip is the shortest way to run it again once the network comes back.
                    recentQueries = (listOf(query) + state.recentQueries.filterNot { it == query }).take(RECENT_LIMIT),
                    searching = false,
                    errorMessage = result.errorMessageOrNull(),
                )
            }
        }
    }

    /**
     * Put a title in «В планах».
     *
     * A failure here lands in [SearchUiState.addFailure] rather than in `errorMessage`: the
     * results are still on the screen and still the thing the viewer came for, so the failure is
     * a message over them rather than instead of them.
     */
    fun addToPlanned(animeId: Int) {
        // Before the coroutine, so the control reads «Добавляем…» on the frame it was pressed and
        // the failure it is retrying disappears with the press rather than a round trip later.
        mutable.update { it.copy(addingAnimeId = animeId, addFailure = null) }
        viewModelScope.launch {
            val result = repository.setStatus(animeId, ListStatus.PLANNED)
            val message = result.errorMessageOrNull()
            mutable.update {
                it.copy(
                    addingAnimeId = null,
                    addFailure = message?.let { text -> AddFailure(animeId, text) },
                )
            }
        }
    }
}
