package app.kaeru.domain.repository

import app.kaeru.domain.discover.Season
import app.kaeru.domain.model.Anime

/**
 * The catalogue outside the viewer's own list: what everyone is watching, and what a season held.
 *
 * Nothing here is stored. These titles are not in anybody's list, so they never reach Room — the
 * home screen shows them while it is open and the repository forgets them when they go stale.
 * That is also why the results are plain [Anime] rather than library entries: there is no rate, no
 * progress and no episode to continue, and a type that carried those fields would have to invent
 * them.
 *
 * Both reads are cached; [force] is what a pull-to-refresh passes to say the viewer has asked for
 * this again and would rather wait than see the same answer.
 */
interface DiscoverRepository {
    /** The titles airing right now, most watched first. */
    suspend fun popularNow(force: Boolean = false): Result<List<Anime>>

    /** The titles that aired in [season], most watched first. */
    suspend fun seasonal(season: Season, force: Boolean = false): Result<List<Anime>>
}
