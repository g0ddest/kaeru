package app.kaeru.domain.playback

import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.PlaybackSampleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * Takes the watched mark off an episode: the one thing [MarkEpisodeWatched] cannot do.
 *
 * Shikimori owns a count rather than a set of ticks, so "episode 5 is not watched" can only be
 * said as "four episodes are". That is the whole use case: the count drops to the episode before
 * this one, which un-marks it and everything after it — and that is the honest reading of a
 * counter anyway, since there is no way to tell it that the fifth is unwatched while the sixth is
 * not.
 *
 * What this device remembers goes with it. A position inside an episode the viewer has just put
 * back in front of them is not where they want to be taken: «продолжить» would offer the episode
 * *after* it, because a row left at nine tenths still reads as finished. So the rows from this
 * episode on are forgotten, and the anime's pointer is rewound if it stands on one of them — in
 * one transaction, through [PlaybackSampleRepository.forgetFrom].
 *
 * Downloads are deliberately untouched. A file on the device is an answer to «I want to watch this
 * on the train», and un-marking an episode is a viewer saying they have not watched it yet — which
 * is a reason to keep the file, never to delete it.
 *
 * Idempotent, like the mark it undoes: a count already below this episode is already saying what
 * this would say, so nothing is sent.
 */
class MarkEpisodeUnwatched(
    private val library: LibraryRepository,
    private val samples: PlaybackSampleRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(animeId: Int, episode: Int): Result<Unit> {
        // There is no episode before the first, and a count of less than nothing is not a thing to
        // send. Nothing local is forgotten either: the viewer named an episode that does not exist.
        if (episode < FIRST_EPISODE) return Result.success(Unit)

        val counted = library.observeAnime(animeId).first()?.rate?.episodes ?: 0
        if (counted >= episode) {
            library.setEpisodes(animeId, episode - 1).getOrElse { return Result.failure(it) }
        }
        forgetPositions(animeId, episode)
        return Result.success(Unit)
    }

    /**
     * Only after the count has actually come down, never before: a write Shikimori refused leaves
     * the episode watched, and throwing away where the viewer got to in it would be losing their
     * place over a failure they are about to retry.
     *
     * A failure here — a logout mid-screen, a disk that will not take the write — is not allowed to
     * fail the un-mark: the mark is off on Shikimori, which is what the viewer asked for, and a
     * stale position costs nothing but a strip on one tile.
     */
    private suspend fun forgetPositions(animeId: Int, episode: Int) {
        try {
            samples.forgetFrom(animeId, episode, clock.instant())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The episode is un-marked either way.
        }
    }

    private companion object {
        /** Where a show starts: the first episode anyone can be asked to forget. */
        const val FIRST_EPISODE = 1
    }
}
