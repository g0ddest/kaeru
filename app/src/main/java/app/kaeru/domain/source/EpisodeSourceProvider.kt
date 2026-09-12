package app.kaeru.domain.source

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Translation

/**
 * Where episodes come from. Kodik is the first implementation; nothing above
 * this interface knows which site the video was scraped from.
 *
 * Both calls answer with [Result] carrying a `domain.error` failure, never a
 * transport exception.
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
}
