package app.kaeru.domain.repository

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState

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
 */
interface PlaybackSampleRepository {
    suspend fun save(watch: WatchState, progress: EpisodeProgress)
}
