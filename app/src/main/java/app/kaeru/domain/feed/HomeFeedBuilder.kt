package app.kaeru.domain.feed

import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import java.time.Duration
import java.time.Instant

class HomeFeedBuilder(
    private val watchedThreshold: Float = 0.9f,
    private val upcomingWindow: Duration = Duration.ofDays(7),
) {
    fun build(entries: List<LibraryEntry>, now: Instant): HomeFeed {
        val active = entries.filter { it.rate.status == ListStatus.WATCHING || it.rate.status == ListStatus.REWATCHING }

        val continueWatching = active
            .filter { e ->
                val w = e.watch ?: return@filter false
                w.episode > e.rate.episodes && w.fraction in 0.01f..<watchedThreshold
            }
            .sortedByDescending { it.watch!!.updatedAt }
            .map { FeedItem(it, it.watch!!.episode, FeedKind.CONTINUE) }
        val inProgressIds = continueWatching.map { it.entry.anime.id }.toSet()

        val newEpisodes = active
            .filter {
                it.anime.status == AnimeStatus.ONGOING &&
                    it.nextEpisode(watchedThreshold) <= it.anime.episodesAired &&
                    it.anime.id !in inProgressIds
            }
            .sortedByDescending { it.anime.nextEpisodeAt ?: it.rate.updatedAt }
            .map { FeedItem(it, it.nextEpisode(watchedThreshold), FeedKind.NEW_EPISODE) }

        val nextUp = active
            .filter {
                it.anime.status != AnimeStatus.ONGOING &&
                    it.nextEpisode(watchedThreshold) <= it.anime.availableEpisodes &&
                    it.anime.id !in inProgressIds
            }
            .sortedByDescending { it.rate.updatedAt }
            .map { FeedItem(it, it.nextEpisode(watchedThreshold), FeedKind.NEXT_UP) }

        val horizon = now.plus(upcomingWindow)
        val upcoming = active
            .filter { e ->
                val next = e.anime.nextEpisodeAt ?: return@filter false
                e.anime.status == AnimeStatus.ONGOING && next.isAfter(now) && next.isBefore(horizon)
            }
            .sortedBy { it.anime.nextEpisodeAt }
            .map { FeedItem(it, it.anime.episodesAired + 1, FeedKind.UPCOMING) }

        val planned = entries
            .filter { it.rate.status == ListStatus.PLANNED }
            .sortedByDescending { it.rate.updatedAt }
            .map { FeedItem(it, 1, FeedKind.PLANNED) }

        val top = continueWatching.firstOrNull() ?: newEpisodes.firstOrNull() ?: nextUp.firstOrNull()
        return HomeFeed(top, continueWatching, newEpisodes, nextUp, upcoming, planned)
    }
}
