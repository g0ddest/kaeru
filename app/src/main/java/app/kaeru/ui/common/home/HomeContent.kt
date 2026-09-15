package app.kaeru.ui.common.home

/**
 * Which of the five screens the home screen is at this moment.
 *
 * The distinction that matters is between an empty list and a list that is not yet known to be
 * empty. Three of these states all have nothing to draw, and each of them means something different
 * to the viewer: the list could not be read ([Error]), the list has not arrived yet ([FirstSync]),
 * or the list really is empty ([Empty]). Inviting someone to add titles they cannot see, or to add
 * titles they already have, would both be headlines that lie. An error carries its message, so the
 * screen cannot render one without having one.
 */
sealed interface HomeContent {
    /** Nothing has come out of the database yet. */
    data object Loading : HomeContent

    /** Nothing to show, and a reason: the cause, and a way to try again. */
    data class Error(val message: String) : HomeContent

    /**
     * Nothing to show yet, because the list is still being read from Shikimori.
     *
     * The state the home screen was missing, and the reason a viewer who had just signed in on the
     * television was told their list was empty: Room answers with an empty list in a millisecond,
     * long before the first sync has fetched anything, so [Empty] was on screen for the whole of
     * it. Waiting is not emptiness, and only a sync that has finished can say a list is empty.
     */
    data object FirstSync : HomeContent

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
        // A refresh behind a feed the viewer is already reading never takes it away, so the feed
        // is answered before anything is said about syncing.
        !state.feed.isEmpty -> HomeContent.Feed
        error != null -> HomeContent.Error(error)
        // Empty and syncing: which of the two it turns out to be is not known until it stops.
        state.isRefreshing -> HomeContent.FirstSync
        else -> HomeContent.Empty
    }
}
