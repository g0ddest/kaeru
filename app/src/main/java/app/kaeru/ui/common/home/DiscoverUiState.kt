package app.kaeru.ui.common.home

import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.seasonChoices
import app.kaeru.domain.model.Anime

/**
 * The part of the home screen that is not about this viewer: what is popular now, and what a
 * season held.
 *
 * These rows are the tail of the screen and none of their problems are the viewer's, so the state
 * says only three things about each of them and the screen stays quiet about the rest:
 *
 * - a list with titles in it — draw the row;
 * - `null` — it could not be read, or there was nothing there. The row is absent. No banner, no
 *   retry: the rows above it still answer the question the viewer opened the app with;
 * - loading — draw the row's heading and a skeleton, so the shape does not jump when it arrives.
 *
 * An empty list is the same answer as `null` on screen, and is kept distinct only because
 * «Shikimori said nothing» and «Shikimori said no» are different things to read in a test.
 */
data class DiscoverUiState(
    /** The season the chips have selected. Starts at the one airing. */
    val season: Season,
    val popularNow: List<Anime>? = null,
    val seasonal: List<Anime>? = null,
    val loadingNow: Boolean = false,
    val loadingSeasonal: Boolean = false,
) {
    /**
     * The three chips, derived rather than stored: a stored copy could disagree with [season]
     * after a bad update, and there is exactly one right answer for any season.
     */
    val seasons: List<Season> get() = seasonChoices(season)
}
