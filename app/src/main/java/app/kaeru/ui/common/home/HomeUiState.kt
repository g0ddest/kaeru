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
)
