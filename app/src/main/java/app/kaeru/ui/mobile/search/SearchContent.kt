package app.kaeru.ui.mobile.search

/**
 * Which of the five screens search is at this moment.
 *
 * The distinction the whole thing turns on is between a search that found nothing and a search
 * that never ran: an untouched screen that says «Ничего не найдено» is telling the viewer they
 * failed at something they have not tried yet. Only a query that came back decides that, which is
 * why [Idle] survives typing and disappears only once a search has completed.
 *
 * A failed write is not on this list on purpose. Adding a title to the list is something the
 * viewer does *over* the results, so when it fails the results stay and the failure goes to a
 * snackbar — taking the grid away would lose the thing they were trying to act on.
 *
 * «A search has run» is `SearchUiState.hasSearched` and nothing else. Inferring it from the
 * remembered queries worked only for as long as those queries died with the screen; the day they
 * are persisted, a freshly opened Search would greet the viewer with «Ничего не найдено».
 */
sealed interface SearchContent {
    /** Nothing has been searched for yet, whatever is in the field. */
    data object Idle : SearchContent

    /** A query is in flight, so the grid is skeletons rather than the last query's answers. */
    data object Loading : SearchContent

    /** Titles to show, in `SearchUiState.results`. */
    data object Results : SearchContent

    /** A query came back with nothing, and a hint about what usually works instead. */
    data object NotFound : SearchContent

    /** The search itself failed: the cause, and a way to try again. */
    data class Error(val message: String) : SearchContent
}

/** The decision, made where it can be read in a test rather than inside a composition. */
fun searchContentState(state: SearchUiState): SearchContent {
    val error = state.errorMessage
    return when {
        state.searching -> SearchContent.Loading
        error != null -> SearchContent.Error(error)
        state.results.isNotEmpty() -> SearchContent.Results
        state.hasSearched -> SearchContent.NotFound
        else -> SearchContent.Idle
    }
}

/** What the card's own control says, and whether it can be pressed. */
data class AddAction(val label: String, val enabled: Boolean)

private const val ADD = "В планы"
private const val ADDING = "Добавляем…"
private const val ADDED = "В списке"

/**
 * The three things that control can say, decided in one place.
 *
 * Already in the list wins over a write in flight: once the list has the title, what the viewer
 * needs to know is that it is there, not that something is still happening about it.
 *
 * It takes the two fields it reads rather than the whole state, so that typing in the field above
 * cannot invalidate a grid that draws none of what changed.
 */
fun addAction(libraryIds: Set<Int>, adding: Int?, animeId: Int): AddAction = when {
    animeId in libraryIds -> AddAction(ADDED, enabled = false)
    animeId == adding -> AddAction(ADDING, enabled = false)
    else -> AddAction(ADD, enabled = true)
}
