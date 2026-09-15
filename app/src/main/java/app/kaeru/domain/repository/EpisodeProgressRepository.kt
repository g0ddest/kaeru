package app.kaeru.domain.repository

import app.kaeru.domain.model.EpisodeProgress
import kotlinx.coroutines.flow.Flow

/**
 * Where in every episode the viewer stopped. One row per anime and episode, beside the single
 * [app.kaeru.domain.model.WatchState] row that says which episode played last and in which
 * translation.
 *
 * Read-only, and that is a statement about the app rather than about this table. Every write to it
 * is half of a playback sample, and a sample's two rows go down together — so the only way to write
 * one is [PlaybackSampleRepository], and there is deliberately no second door. A `save` here would
 * be a way to put the episode's row and the anime's pointer out of step with each other, which is
 * exactly what the single transaction exists to rule out.
 */
interface EpisodeProgressRepository {
    /** Every episode of one anime this device has a position for. */
    fun observe(animeId: Int): Flow<List<EpisodeProgress>>
}
