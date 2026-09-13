package app.kaeru.domain.repository

import app.kaeru.domain.model.EpisodeProgress
import kotlinx.coroutines.flow.Flow

/**
 * Where in every episode the viewer stopped. One row per anime and episode, beside the single
 * [app.kaeru.domain.model.WatchState] row that says which episode played last and in which
 * translation.
 *
 * Writes are account-owned exactly as watch states are, so [save] and [clear] signal by throwing a
 * `domain.error` failure rather than returning a `Result`: no caller on the playback path has
 * anything to say to the viewer about a dropped position sample.
 */
interface EpisodeProgressRepository {
    /** Every episode of one anime this device has a position for. */
    fun observe(animeId: Int): Flow<List<EpisodeProgress>>

    /** Every row there is, for the library, which builds each entry out of one snapshot. */
    fun observeAll(): Flow<List<EpisodeProgress>>

    suspend fun save(progress: EpisodeProgress)

    /** Forgets every episode of one anime, the way removing it from the list should. */
    suspend fun clear(animeId: Int)
}
