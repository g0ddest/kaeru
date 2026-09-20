package app.kaeru.data.playback

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.PlaybackSampleRepository
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One progress sample, written to both of its tables in one transaction.
 *
 * This is the only writer in the data layer that takes the database rather than a DAO, and that is
 * the whole point of it: the two rows belong to one moment of playback, and Room's transaction is
 * what makes them arrive as one — including when an un-marked episode takes them away again.
 * Everything else about it is the same as the two repositories whose rows it writes — the account
 * lock once, and SQLite's failures translated on the way out.
 */
@Singleton
class RoomPlaybackSampleRepository @Inject constructor(
    private val database: KaeruDatabase,
    private val session: AccountSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackSampleRepository {

    override suspend fun save(watch: WatchState, progress: EpisodeProgress) =
        accountWrite(session, io) { database.savePlaybackSample(watch.toEntity(), progress.toEntity()) }

    override suspend fun forgetFrom(animeId: Int, episode: Int, at: Instant) =
        accountWrite(session, io) { database.forgetProgressFrom(animeId, episode, at) }

    override suspend fun restore(progress: List<EpisodeProgress>) =
        accountWrite(session, io) { database.restoreProgress(progress.map { it.toEntity() }) }
}
