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
 *
 * [event] counts failures rather than describing one. Two adds failing on two different titles
 * produce the same words, and a snackbar that keys on the words would announce the first and
 * silently swallow the second.
 */
data class AddFailure(val animeId: Int, val message: String, val event: Long)

data class SearchUiState(
    val query: String = "",
    val results: List<Anime> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val searching: Boolean = false,
    /** A query has come back at least once, which is what tells «nothing found» from «not asked». */
    val hasSearched: Boolean = false,
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

    /**
     * The query that actually went to the repository.
     *
     * Kept apart from the field because the two come apart: a viewer who clears the field while an
     * error is on screen still has a failed search to retry, and `submit()` reading the field would
     * find nothing to send.
     */
    private var lastSentQuery: String? = null

    private var failures = 0L

    init {
        viewModelScope.launch {
            repository.observeLibrary().collect { entries ->
                val ids = entries.mapTo(mutableSetOf()) { it.anime.id }
                mutable.update { state ->
                    state.copy(
                        libraryIds = ids,
                        // A pending add ends when the list actually holds the title, not when the
                        // write returns. Room's invalidation is a hop behind the write, and clearing
                        // it earlier puts «В планы» back under the card for a frame or two —
                        // inviting a second press of something that has just succeeded.
                        addingAnimeId = state.addingAnimeId?.takeUnless { it in ids },
                    )
                }
            }
        }
    }

    fun setQuery(value: String) = mutable.update { it.copy(query = value) }

    fun useRecent(value: String) { setQuery(value); submit() }

    /** The keyboard's search key, and the chips: whatever is in the field goes. */
    fun submit() {
        val query = mutable.value.query.trim()
        if (query.length < MIN_QUERY || mutable.value.searching) return
        search(query)
    }

    /**
     * The error state's «Повторить»: the query that failed goes again, whatever the field says now.
     *
     * It also writes that query back into the field, so what is being searched is what the viewer
     * can see. Nothing sent yet means nothing to retry, and the button is not on screen anyway.
     */
    fun retry() {
        val query = lastSentQuery ?: return
        if (mutable.value.searching) return
        search(query)
    }

    private fun search(query: String) {
        lastSentQuery = query
        // Marked before the coroutine starts, so `submit`'s guard catches a double press and the
        // field shows the query being searched on the same frame as the key.
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
                    hasSearched = true,
                    errorMessage = result.errorMessageOrNull(),
                )
            }
        }
    }

    /**
     * Put a title in «В планах».
     *
     * A failure lands in [SearchUiState.addFailure] rather than in `errorMessage`: the results are
     * still on the screen and still the thing the viewer came for, so the failure is a message over
     * them rather than instead of them. A success says nothing at all — the pending mark stays on
     * the card until the library flow shows the title in the list.
     */
    fun addToPlanned(animeId: Int) {
        // Before the coroutine, so the control reads «Добавляем…» on the frame it was pressed and
        // the failure it is retrying disappears with the press rather than a round trip later.
        mutable.update { it.copy(addingAnimeId = animeId, addFailure = null) }
        viewModelScope.launch {
            val message = repository.setStatus(animeId, ListStatus.PLANNED).errorMessageOrNull() ?: return@launch
            // Counted outside `update`, which may run its lambda more than once under contention.
            failures += 1
            val failure = AddFailure(animeId, message, failures)
            mutable.update { it.copy(addingAnimeId = null, addFailure = failure) }
        }
    }
}
