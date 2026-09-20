package app.kaeru.domain.feed

import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
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
     * @param downloads every download the engine knows about, in every state. Defaulted empty
     *   because the television has none and never will.
     */
    fun build(
        entries: List<LibraryEntry>,
        now: Instant,
        watchedThreshold: Float,
        downloads: List<EpisodeDownload> = emptyList(),
    ): HomeFeed {
        // The target is worked out once per title and carried through every row. It used to be
        // derived four times over — once here and once inside each `nextEpisode` call — and the
        // three rows below all ask the same question of it.
        val targeted = entries.map { it to it.continueTarget(watchedThreshold) }
        val active = targeted
            .filter { (entry, _) ->
                entry.rate.status == ListStatus.WATCHING || entry.rate.status == ListStatus.REWATCHING
            }

        // An entry is being continued exactly when its target carries a position: the rule for
        // which episode that is, and for what counts as started rather than mis-tapped, lives once
        // in `ContinueTarget` and is the same one the watch button obeys.
        //
        // Every entry but one, where the rest of the screen looks at two statuses. A position is a
        // fact about this device and about nothing else: a title opened out of search sits in
        // «Запланировано» until the mark at nine tenths picks it up, and one paused for a month is
        // «Отложено» on purpose. Both were left in the middle of an episode, and a viewer who has
        // to search for that episode again is a viewer the row failed.
        //
        // «Брошено» is deliberately not a second exception. It is the viewer's word about the show
        // and not about the episode, and a dropped show whose fifth episode is half watched is a
        // show somebody stopped in the middle of — which is exactly the question this row answers.
        // The card is one press away from being dismissed by finishing or restatusing it, and the
        // alternative is the fault this whole row was fixed for: a position nothing on the home
        // screen will admit to.
        //
        // «Завершено» is the one exception, and it is named here rather than left to the target. Most
        // finished shows are excluded by the count — `ContinueTarget` will not resume an episode
        // Shikimori has already counted — but the count is not what says the viewer is done: the
        // title screen writes the status on its own. Somebody who stops half-way through the fifth
        // episode and then marks the show finished keeps a position ahead of a count of four, and
        // the row would hand them back the very episode they had just declared themselves done
        // with, at the top of the screen.
        val continueWatching = targeted
            .filter { (entry, target) ->
                target.positionMs > 0 && entry.rate.status != ListStatus.COMPLETED
            }
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

        // A title already being continued is not also something to plan: it is one title, and two
        // cards for it on one screen would be the screen arguing with itself about where the
        // viewer is in it. The same exclusion the two rows above make, for the same reason.
        val planned = entries
            .filter { it.rate.status == ListStatus.PLANNED && it.anime.id !in inProgressIds }
            .sortedByDescending { it.rate.updatedAt }
            .map { FeedItem(it, 1, FeedKind.PLANNED) }

        // Every entry, not just the active ones: an episode on the device is worth offering
        // whatever the list says about the title it came from — a show marked «Завершено» whose
        // finale is downloaded is still a finale somebody can watch on a train.
        val known = entries.associateBy { it.anime.id }
        val downloaded = downloads
            .filter { it.state == DownloadState.COMPLETED }
            // Newest download first, which is the order the viewer put them there in. Nothing
            // else would do: these episodes have no other relationship to each other.
            .sortedByDescending { it.updatedAt }
            .mapNotNull { row ->
                // A download whose title is in no list has no card to draw — there is no artwork,
                // no name and no count. The «Загрузки» screen names it by its id; a poster row
                // cannot.
                val entry = known[row.animeId] ?: return@mapNotNull null
                if (entry.isBehind(row.episode, watchedThreshold)) return@mapNotNull null
                FeedItem(entry, row.episode, FeedKind.DOWNLOADED)
            }

        // The hero is never a download. «Скачано» is about where the episode is, not about what
        // the viewer was in the middle of, and a card that stole the top of the screen from an
        // episode left half-watched would be answering a question nobody asked.
        val top = continueWatching.firstOrNull() ?: newEpisodes.firstOrNull() ?: nextUp.firstOrNull()
        return HomeFeed(top, continueWatching, newEpisodes, nextUp, upcoming, planned, downloaded)
    }
}

/**
 * Whether this episode is already behind the viewer, either way it can be.
 *
 * Shikimori's count is one of them and what this device saw is the other, and they are checked
 * separately because they fail separately: a mark that has not reached the server yet leaves only
 * the local position, and a title watched on another device leaves only the count.
 *
 * An episode with no position at all is not behind anybody — it is exactly what «Скачано» is for.
 */
private fun LibraryEntry.isBehind(episode: Int, watchedThreshold: Float): Boolean {
    if (episode <= rate.episodes) return true
    val row = progressAt(episode) ?: return false
    return !row.unfinished(watchedThreshold)
}
