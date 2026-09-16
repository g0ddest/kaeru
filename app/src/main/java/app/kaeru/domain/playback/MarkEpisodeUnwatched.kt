package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.repository.EpisodeProgressRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.PlaybackSampleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * What un-marking an episode took away, which is what putting it back has to know.
 *
 * Shikimori holds a count, so «episode 5 is not watched» is said as «four are» — and that takes
 * episodes 6 and 7 with it when the viewer had watched through 7. [previousCount] is the only
 * record of those two: without it an undo can restore the episode the viewer tapped and nothing
 * else, quietly abandoning the rest of their history.
 *
 * [forgotten] is the per-episode positions that went at the same time. They are not what the grid
 * draws once the count is back — an episode behind the count wears a check, not a strip — but they
 * are what says when this title was last actually watched, which is what orders «Продолжить» on
 * the home screen.
 */
data class UnwatchedOutcome(
    val episode: Int,
    val previousCount: Int,
    val forgotten: List<EpisodeProgress>,
)

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
 * this would say, so nothing is sent and nothing local is thrown away.
 *
 * Everything it took is returned in an [UnwatchedOutcome], and [restore] is how that is put back.
 */
class MarkEpisodeUnwatched(
    private val library: LibraryRepository,
    private val progress: EpisodeProgressRepository,
    private val samples: PlaybackSampleRepository,
    private val suppressed: SuppressedMarks,
    private val clock: Clock,
) {
    suspend operator fun invoke(animeId: Int, episode: Int): Result<UnwatchedOutcome> {
        val counted = library.observeAnime(animeId).first()?.rate?.episodes ?: 0
        // There is no episode before the first, and a count of less than nothing is not a thing to
        // send. Nothing local is forgotten either: the viewer named an episode that does not exist.
        if (episode < FIRST_EPISODE) return Result.success(UnwatchedOutcome(episode, counted, emptyList()))

        // A count already below this episode says what the write would say, so there is nothing to
        // send — and, just as much, nothing to forget: the positions belong to an episode whose
        // watched state this call is not changing.
        if (counted < episode) return Result.success(UnwatchedOutcome(episode, counted, emptyList()))

        // Read before the write, because the write is what makes them worth keeping: an undo has to
        // be able to put back exactly what this call is about to take.
        val losing = progress.observe(animeId).first().filter { it.episode >= episode }.sortedBy { it.episode }
        library.setEpisodes(animeId, episode - 1).getOrElse { return Result.failure(it) }
        forgetPositions(animeId, episode)
        // Whatever is playing this episode right now must not quietly count it again: a cast
        // session and picture-in-picture both outlive the screen this was pressed on.
        suppressed.suppress(animeId, episode)
        return Result.success(UnwatchedOutcome(episode, counted, losing))
    }

    /**
     * «Отменить»: the count and the positions as they stood before [invoke].
     *
     * The count rather than the episode, which is the whole point of carrying the outcome around.
     * Re-marking the episode the viewer tapped would set the count to *that* number, so a viewer
     * who had watched through seven and un-marked the fifth would get five back and lose two
     * episodes of history to the control that exists to undo the loss.
     *
     * Through [LibraryRepository.setEpisodes] like every other write to that number, so an undo
     * with no network queues in the outbox instead of failing.
     */
    suspend fun restore(animeId: Int, outcome: UnwatchedOutcome): Result<Unit> {
        val counted = library.observeAnime(animeId).first()?.rate?.episodes ?: 0
        if (counted < outcome.previousCount) {
            library.setEpisodes(animeId, outcome.previousCount).getOrElse { return Result.failure(it) }
        }
        restorePositions(outcome.forgotten)
        // The mark is back, so there is nothing for the player to contradict any more.
        suppressed.release(animeId, outcome.episode)
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

    /**
     * The rows the un-mark deleted, put back as they were.
     *
     * Best effort for the same reason and with the same consequence reversed: the count is what the
     * viewer sees, and it is already back. The anime's pointer keeps the zero the un-mark gave it —
     * it stands for an episode that now has a row of its own again, and that row is what every
     * surface reads.
     */
    private suspend fun restorePositions(rows: List<EpisodeProgress>) {
        if (rows.isEmpty()) return
        try {
            samples.restore(rows)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The mark is back either way.
        }
    }

    private companion object {
        /** Where a show starts: the first episode anyone can be asked to forget. */
        const val FIRST_EPISODE = 1
    }
}
