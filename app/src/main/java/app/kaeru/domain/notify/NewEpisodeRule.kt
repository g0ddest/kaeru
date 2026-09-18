package app.kaeru.domain.notify

import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus

/**
 * What one check found: what to say, and what to write down so it is never said twice.
 *
 * The two lists are not the same length and neither contains the other. An episode already begun
 * is written down and not announced; the highest episode a title has reached is written down
 * whether or not it is the one being announced.
 */
data class NewEpisodeCheck(
    val news: List<NewEpisode>,
    val record: List<NotifiedEpisode>,
)

/**
 * Which titles have an episode out that the viewer has not been told about.
 *
 * Pure, and the whole of the decision: the worker around it only fetches, stores and publishes.
 *
 * **What counts as new.** Three questions, and a title has to answer all three. It has to be one
 * the viewer is actually watching — «Запланировано» is a wish list and would arrive as an
 * avalanche on the first night. It has to have an episode out that they have not watched. And that
 * episode has to have come out *since the last check*: a count that has not moved is not news,
 * however far behind the viewer is.
 *
 * **Why the first sighting is silent.** A title with no rows of its own has never been looked at —
 * a fresh install, a sign-in, a title added an hour ago — and the honest thing to say about it is
 * nothing. Announcing it would mean a viewer who installs the app on a Sunday gets one notification
 * per ongoing title they follow, for episodes that came out days ago. So the first sighting writes
 * down what it saw and says nothing, and the rule the brief asks for — «the first check after
 * installing shows nothing» — is the case where every title is newly sighted, rather than a flag of
 * its own that a sign-out would have to remember to clear.
 *
 * **Which episode is named.** The first one unwatched, which is the one a viewer would press play
 * on, and not the one that has just aired: somebody three episodes behind is not helped by a card
 * that starts the tenth. A pair is announced once and never again, so falling further behind is
 * quiet — the rows say the sixth has already been offered, and the seventh is not offered until
 * the sixth is out of the way.
 */
object NewEpisodeRule {

    /**
     * @param known every pair the checker has written down so far, in any order.
     */
    fun check(entries: List<LibraryEntry>, known: List<NotifiedEpisode>): NewEpisodeCheck {
        val pairs = known.toHashSet()
        val highest = known.groupBy { it.animeId }.mapValues { (_, rows) -> rows.maxOf { it.episode } }
        val news = mutableListOf<NewEpisode>()
        val record = mutableListOf<NotifiedEpisode>()

        for (entry in entries) {
            if (entry.rate.status != ListStatus.WATCHING && entry.rate.status != ListStatus.REWATCHING) continue
            val available = entry.anime.availableEpisodes
            // An announcement with nothing out yet is not a title that has gone quiet, it is one
            // that has not started. Writing a zero down would make the first episode look old.
            if (available < 1) continue

            val seen = highest[entry.anime.id]
            if (seen == null) {
                record += NotifiedEpisode(entry.anime.id, available)
                continue
            }
            if (available <= seen) continue
            record += NotifiedEpisode(entry.anime.id, available)

            val watched = entry.rate.episodes
            if (available <= watched) continue
            val episode = watched + 1
            if (NotifiedEpisode(entry.anime.id, episode) in pairs) continue

            // Somebody already part-way into the episode does not need to be told it exists. The
            // pair is written down anyway, or the next check would find it unannounced and say so
            // the moment they paused.
            if (entry.progressAt(episode)?.started == true) {
                if (episode != available) record += NotifiedEpisode(entry.anime.id, episode)
                continue
            }
            news += NewEpisode(
                animeId = entry.anime.id,
                title = entry.anime.title,
                posterUrl = entry.anime.posterUrl,
                episode = episode,
            )
            if (episode != available) record += NotifiedEpisode(entry.anime.id, episode)
        }
        return NewEpisodeCheck(news, record)
    }
}
