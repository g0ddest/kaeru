package app.kaeru.domain.source

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Translation

/**
 * Where episodes come from. Kodik is the first implementation; nothing above
 * this interface knows which site the video was scraped from.
 *
 * The calls that reach the network answer with [Result] carrying a `domain.error` failure,
 * never a transport exception.
 */
interface EpisodeSourceProvider {
    /** The tracks this source offers for an anime, in the order the source lists them. */
    suspend fun translations(shikimoriId: Int): Result<List<Translation>>

    /**
     * Signed, short-lived URLs for one episode. Resolve immediately before
     * playback: the links expire in hours and look IP-bound.
     *
     * @param translation null lets the source pick the track it lists first.
     */
    suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation? = null): Result<EpisodeStream>

    /**
     * The episode numbers this source has already seen listed for one track, or null when it has
     * never read that track's page. Memory only — this never asks the network — so a null is «not
     * known», never «not there». Dropped together with whatever else is cached about the anime.
     */
    suspend fun listedEpisodes(shikimoriId: Int, translationId: Int): Set<Int>? = null

    /**
     * Forgets everything cached about this anime, so the next question reaches the source.
     * A viewer pressing «Повторить» on an episode that has since appeared must not be answered
     * out of a cache that predates it.
     */
    suspend fun forget(shikimoriId: Int) = Unit
}
