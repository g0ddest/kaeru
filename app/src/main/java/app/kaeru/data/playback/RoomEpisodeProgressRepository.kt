package app.kaeru.data.playback

import app.kaeru.data.local.EpisodeProgressDao
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.repository.EpisodeProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-episode positions in Room, one row per anime and episode.
 *
 * Reads only, and therefore no account lock: a library refresh can hold that lock for seconds, and
 * the player must never wait on it to learn where to resume. The rows are still account-owned —
 * they go with the account, in `KaeruDatabase.clearAccountData` — and the one thing that writes
 * them is [RoomPlaybackSampleRepository], which does take the lock.
 */
@Singleton
class RoomEpisodeProgressRepository @Inject constructor(
    private val dao: EpisodeProgressDao,
) : EpisodeProgressRepository {

    override fun observe(animeId: Int): Flow<List<EpisodeProgress>> = dao.observeByAnime(animeId)
        .map { rows -> rows.map { it.toDomain() } }
        // Room invalidates per table: without this, every other anime's progress sample would
        // wake up whoever is watching this one.
        .distinctUntilChanged()
}
