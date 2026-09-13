package app.kaeru.ui.common.home

import app.kaeru.domain.discover.Season
import app.kaeru.domain.model.HomeFeed
import java.time.Instant
import java.time.ZoneId

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
     * The catalogue rows under the personal ones.
     *
     * The default reads the clock because a season is a fact about today and there is no honest
     * placeholder for it; the view model always passes its own, so this is only what a preview or
     * a screen with no discovery of its own gets.
     */
    val discover: DiscoverUiState = DiscoverUiState(Season.current(Instant.now(), ZoneId.systemDefault())),
)
