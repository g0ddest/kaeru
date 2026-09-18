package app.kaeru.domain.notify

import java.time.Instant

/**
 * One episode the checker has already seen, and will therefore never present as news again.
 *
 * A row means «known», which is a little wider than «announced»: the highest episode a check found
 * available is written down whether or not anything was said about it, and that is what makes the
 * next check able to tell a title that has moved on from one that has not. See [NewEpisodeRule].
 */
data class NotifiedEpisode(val animeId: Int, val episode: Int)

/** What the checker remembers between runs. Durable, or the first night would repeat itself. */
interface NotifiedEpisodes {
    suspend fun all(): List<NotifiedEpisode>

    /** Writing a pair that is already there changes nothing, including the time it was written. */
    suspend fun record(episodes: List<NotifiedEpisode>, at: Instant)
}
