package app.kaeru.ui.common.home

import app.kaeru.domain.model.HomeFeed

data class HomeUiState(
    val feed: HomeFeed = HomeFeed.EMPTY,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /**
     * How much of an episode has to be behind the viewer for it to count as watched.
     *
     * The screen needs it for two answers the feed does not carry: whether a card still has a
     * progress strip, and which episode the watch button offers. The default matches
     * `HomeFeedBuilder`'s, so the label and the feed always name the same episode.
     */
    val watchedThreshold: Float = 0.9f,
    /**
     * The catalogue rows under the personal ones, or null for a screen that has none.
     *
     * Null rather than a default, because the only honest default needs today's date and a data
     * class whose default reads the wall clock is a trap for the next reader. The view model always
     * supplies one from its injected [java.time.Clock]; a preview, or a television that draws no
     * catalogue, simply leaves it out.
     */
    val discover: DiscoverUiState? = null,
)
