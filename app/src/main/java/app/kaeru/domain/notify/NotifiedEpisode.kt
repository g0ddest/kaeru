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
    /**
     * What is remembered about these titles, in any order.
     *
     * Asked about the titles a check is actually looking at rather than about everything, because
     * the table outlives a title's stay in «Смотрю» and a phone three years old would hand over
     * thousands of rows to answer a question about forty.
     */
    suspend fun forAnime(animeIds: List<Int>): List<NotifiedEpisode>

    /** Writing a pair that is already there changes nothing, including the time it was written. */
    suspend fun record(episodes: List<NotifiedEpisode>, at: Instant)

    /**
     * Throws the whole memory away, so the next check is a first sighting of everything.
     *
     * What turning the switch back on means. Nothing was watching while it was off, so every row
     * is a note about a state that may be months stale, and believing them would let one evening's
     * check announce a season's worth of episodes at once. Forgetting instead makes the next run
     * write down what is out and say nothing, exactly as a fresh install does.
     */
    suspend fun forget()
}
