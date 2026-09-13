package app.kaeru.domain.feed

import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import java.time.Duration
import java.time.Instant

class HomeFeedBuilder(private val upcomingWindow: Duration = Duration.ofDays(7)) {
    /**
     * @param watchedThreshold how much of an episode counts as watched, as the viewer set it.
     *   A parameter of the call rather than of the builder, and deliberately without a default:
     *   this is a singleton, the setting changes while it is alive, and every label drawn around
     *   the feed reads the live value. A builder holding 0.9 of its own is a hero whose button
     *   says «Продолжить 7 серию» and starts the sixth.
     */
    fun build(entries: List<LibraryEntry>, now: Instant, watchedThreshold: Float): HomeFeed {
        val active = entries.filter { it.rate.status == ListStatus.WATCHING || it.rate.status == ListStatus.REWATCHING }

        // An entry is being continued exactly when its target carries a position: the rule for
        // which episode that is, and for what counts as started rather than mis-tapped, lives once
        // in `ContinueTarget` and is the same one the watch button obeys.
        val continueWatching = active
            .map { it to it.continueTarget(watchedThreshold) }
            .filter { (_, target) -> target.positionMs > 0 }
            // Ordered by when the title itself was last watched, not by the target episode's own
            // row: going back to an earlier episode on purpose leaves the card pointing at the
            // later one, and a row sorted on that stale timestamp would sink the very title the
            // viewer had open five minutes ago.
            .sortedByDescending { (entry, _) -> entry.episodeProgress.maxOfOrNull { it.updatedAt } ?: Instant.EPOCH }
            .map { (entry, target) -> FeedItem(entry, target.episode, FeedKind.CONTINUE) }
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
