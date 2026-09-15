package app.kaeru.domain.playback

import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * What marking an episode did, so the screen can react without asking again.
 *
 * [suggestCompleted] is only ever a suggestion: the last episode opens a dialog,
 * because "watched the finale" and "finished the show" are the viewer's call, not ours.
 */
data class WatchedOutcome(
    val markedEpisode: Int,
    val movedToWatching: Boolean,
    val suggestCompleted: Boolean,
)

/**
 * Records an episode as watched on Shikimori, which owns that number — this app only
 * ever raises it.
 *
 * Idempotent by reading that number first: a rewind past the threshold, a second
 * player, or a re-entered episode all find the count already high enough and send
 * nothing. An anime the viewer only planned (or shelved) is picked back up first,
 * and one that was never in the list at all is added, since there is no rate to
 * raise otherwise.
 */
class MarkEpisodeWatched(
    private val library: LibraryRepository,
    private val watchStates: WatchStateRepository,
    private val clock: Clock,
    private val downloads: DownloadRepository,
    private val settings: SettingsStore,
) {
    suspend operator fun invoke(animeId: Int, episode: Int): Result<WatchedOutcome> {
        val known = library.observeAnime(animeId).first()
        val status = known?.rate?.status
        // Watching it is the fact on the ground; the list status is what disagrees.
        val pickUp = known == null || status == ListStatus.PLANNED || status == ListStatus.ON_HOLD
        if (pickUp) library.setStatus(animeId, ListStatus.WATCHING).getOrElse { return Result.failure(it) }

        // A rate that was just created has to be read back: only then is there a count to compare.
        val entry = known ?: library.observeAnime(animeId).first()
        val alreadyCounted = (entry?.rate?.episodes ?: 0) >= episode
        if (!alreadyCounted) {
            library.setEpisodes(animeId, episode).getOrElse { return Result.failure(it) }
            rewindOvertakenPosition(animeId, episode)
            deleteWatchedDownload(animeId, episode)
        }

        val announced = entry?.anime?.episodes ?: 0
        return Result.success(
            WatchedOutcome(
                markedEpisode = episode,
                movedToWatching = pickUp,
                // Nothing new was counted, so the dialog was already offered when it was.
                suggestCompleted = !alreadyCounted && announced > 0 && episode >= announced,
            ),
        )
    }

    /**
     * «Удалять просмотренные»: an episode that has just been counted gives its space back.
     *
     * Three conditions, and each rules out a different way this could go wrong. The setting is off
     * by default, because deleting somebody's episode is not a default. Only a download that has
     * *finished* is touched — a queue set up for tonight is not something a mark should quietly
     * cancel, and a half-downloaded episode is going to finish and then be deleted anyway, which
     * would spend the data for nothing. And it runs only on the branch where something was newly
     * counted, so re-entering an episode the server already knew about never deletes it from under
     * a viewer who is watching it again.
     *
     * A removal that fails costs nothing but space, so it is not allowed to fail the mark.
     */
    private suspend fun deleteWatchedDownload(animeId: Int, episode: Int) {
        try {
            if (!settings.downloadPolicy.first().deleteWatched) return
            if (downloads.completed(animeId, episode) == null) return
            downloads.remove(animeId, episode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The episode is marked either way; the space is given back the next time it is asked for.
        }
    }

    /**
     * A position inside an episode Shikimori has now passed is spent: it would only offer to
     * resume something already watched. The row is rewound rather than deleted, because it also
     * carries which track and Kodik season this anime plays in, and losing that would re-pick a
     * voice mid-show. The episode just marked keeps its position, because the player is still
     * playing it.
     */
    private suspend fun rewindOvertakenPosition(animeId: Int, markedEpisode: Int) {
        val watch = watchStates.observe(animeId).first() ?: return
        if (watch.episode >= markedEpisode) return
        try {
            watchStates.save(watch.copy(positionMs = 0, durationMs = 0, updatedAt = clock.instant()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The episode is marked either way; a stale position costs nothing but a stale card.
        }
    }
}
