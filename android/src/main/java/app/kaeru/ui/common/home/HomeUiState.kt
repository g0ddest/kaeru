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
     * progress strip, and which episode the watch button offers. It is the same value [feed] was
     * built with — one read of the setting per state — so the label and the feed always name the
     * same episode. The default is only for a preview or a screen with no feed yet.
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
    /**
     * There is no network, so the screen says so in one line and stops offering what it cannot
     * give: [discover] is null for as long as this is true.
     *
     * A fact about the device rather than about a request that failed — it is true before anything
     * has been asked for, which is what lets the strip appear the moment the network goes rather
     * than at the end of the next failed refresh.
     */
    val offline: Boolean = false,
    /**
     * The version of a newer release this device has heard about, or null.
     *
     * A string rather than the release itself, because a one-line row needs nothing else and the
     * home screen has no business knowing where an APK lives. It comes from the last completed
     * check, which means it survives a restart and appears with no network — and it stays null on
     * a device that is already on the newest version, which is the only honest answer there.
     */
    val updateVersion: String? = null,
)
