package app.kaeru.ui.common.home

import app.kaeru.ui.common.home.HomeUiState

/**
 * Which of the four screens the home screen is at this moment.
 *
 * The distinction that matters is between an empty list and a list that could not be read: a fresh
 * install with no network has both an empty feed and a failed refresh, and inviting the viewer to
 * add titles they cannot see would be a headline that lies. An error carries its message, so the
 * screen cannot render one without having one.
 */
sealed interface HomeContent {
    /** Nothing has come out of the database yet. */
    data object Loading : HomeContent

    /** Nothing to show, and a reason: the cause, and a way to try again. */
    data class Error(val message: String) : HomeContent

    /** Nothing to show and nothing wrong — a list nobody has filled in yet. */
    data object Empty : HomeContent

    /** Something to watch. A refresh that failed over a feed this full is a snackbar, not a screen. */
    data object Feed : HomeContent
}

/** The decision, made where it can be read in a test rather than inside a composition. */
fun homeContentState(state: HomeUiState): HomeContent {
    val error = state.errorMessage
    return when {
        state.isLoading -> HomeContent.Loading
        !state.feed.isEmpty -> HomeContent.Feed
        error != null -> HomeContent.Error(error)
        else -> HomeContent.Empty
    }
}
