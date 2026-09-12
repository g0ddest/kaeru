package app.kaeru.domain.repository

import app.kaeru.domain.model.WatchState
import kotlinx.coroutines.flow.Flow

/**
 * Where in an episode the viewer stopped. One row per anime: the local answer to
 * "continue watching", and the memory of which translation and Kodik season the
 * anime was last played with.
 *
 * Writes are account-owned, so [save] and [clear] can fail (a logout mid-playback
 * throws `AccountSessionChanged`); they signal by throwing a `domain.error`
 * failure rather than by returning a `Result`, because no caller on the playback
 * path has anything to say to the user about a dropped position sample.
 */
interface WatchStateRepository {
    fun observe(animeId: Int): Flow<WatchState?>
    suspend fun save(state: WatchState)
    suspend fun clear(animeId: Int)
}
