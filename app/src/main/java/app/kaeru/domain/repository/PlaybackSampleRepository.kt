package app.kaeru.domain.repository

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import java.time.Instant

/**
 * The two rows one progress sample leaves behind, written as one thing.
 *
 * A sample says where the viewer is, and that lands in two places: the episode's own row, which is
 * what «продолжить» is read from and what survives opening another episode, and the anime's single
 * pointer row, which says which episode played last and in which track. They were written one after
 * the other, and each write took the account lock on its own — twice every few seconds, with a
 * library refresh able to hold that lock for a second or two in between. Between the two writes the
 * database also said two different things about where the viewer was.
 *
 * One call, one lock, one transaction: both rows or neither. A sample is worth nothing on its own —
 * the next one is seconds away — so losing one whole is cheaper than keeping half of one.
 *
 * The same pair, taken away rather than written, is [forgetFrom]: un-marking an episode has to
 * undo both halves at once for the same reason writing them apart was wrong.
 */
interface PlaybackSampleRepository {
    suspend fun save(watch: WatchState, progress: EpisodeProgress)

    /**
     * Forgets where the viewer was in [episode] and in everything after it.
     *
     * The other half of a sample, and here for the same reason: an episode being un-marked has to
     * lose its own row *and* let go of the anime's pointer, or the pointer puts the position
     * straight back — [app.kaeru.domain.model.LibraryEntry] reads it as the row for the episode it
     * stands on when the table has none. Two writes would leave a window in which the episode is
     * no longer counted and still half-watched, which is «продолжить» offering the episode after
     * the one the viewer just asked to go back to.
     *
     * The pointer is rewound rather than deleted: it also carries which track and Kodik season
     * this anime plays in, and losing that would re-pick a voice mid-show. A pointer on an earlier
     * episode is left alone — nothing about it changed.
     *
     * @param at the moment to stamp the rewound pointer with.
     */
    suspend fun forgetFrom(animeId: Int, episode: Int, at: Instant)
}
