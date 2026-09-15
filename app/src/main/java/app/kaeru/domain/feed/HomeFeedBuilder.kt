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
        // The target is worked out once per title and carried through every row. It used to be
        // derived four times over — once here and once inside each `nextEpisode` call — and the
        // three rows below all ask the same question of it.
        val active = entries
            .filter { it.rate.status == ListStatus.WATCHING || it.rate.status == ListStatus.REWATCHING }
            .map { it to it.continueTarget(watchedThreshold) }

        // An entry is being continued exactly when its target carries a position: the rule for
        // which episode that is, and for what counts as started rather than mis-tapped, lives once
        // in `ContinueTarget` and is the same one the watch button obeys.
        val continueWatching = active
            .filter { (_, target) -> target.positionMs > 0 }
            // Ordered by when the title itself was last watched, not by the target episode's own
            // row: going back to an earlier episode on purpose leaves the card pointing at the
            // later one, and a row sorted on that stale timestamp would sink the very title the
            // viewer had open five minutes ago. Rows nobody really started are left out of the
            // answer, or a tap on the wrong tile would carry a title to the head of the row.
            .sortedByDescending { (entry, _) -> entry.lastWatchedAt() ?: Instant.EPOCH }
            .map { (entry, target) -> FeedItem(entry, target.episode, FeedKind.CONTINUE) }
        val inProgressIds = continueWatching.map { it.entry.anime.id }.toSet()

        val newEpisodes = active
            .filter { (entry, target) ->
                entry.anime.status == AnimeStatus.ONGOING &&
                    !target.rewatch &&
                    target.episode <= entry.anime.episodesAired &&
                    entry.anime.id !in inProgressIds
            }
            .sortedByDescending { (entry, _) -> entry.anime.nextEpisodeAt ?: entry.rate.updatedAt }
            .map { (entry, target) -> FeedItem(entry, target.episode, FeedKind.NEW_EPISODE) }

        // A show whose every episode is behind the viewer is left out of both rows, however it got
        // that way — Shikimori's count alone, or episodes finished here the server has not heard
        // about. «Дальше по списку» is about what to watch next, and there is no next; the offer
        // to see it again belongs on the title screen, where the viewer went looking for it.
        val nextUp = active
            .filter { (entry, target) ->
                entry.anime.status != AnimeStatus.ONGOING &&
                    !target.rewatch &&
                    target.episode <= entry.anime.availableEpisodes &&
                    entry.anime.id !in inProgressIds
            }
            .sortedByDescending { (entry, _) -> entry.rate.updatedAt }
            .map { (entry, target) -> FeedItem(entry, target.episode, FeedKind.NEXT_UP) }

        val horizon = now.plus(upcomingWindow)
        val upcoming = active
            .map { (entry, _) -> entry }
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
