package app.kaeru.data.playback

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.EpisodeProgressDao
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.repository.EpisodeProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-episode positions in Room, one row per anime and episode.
 *
 * The same account rules as [RoomWatchStateRepository], for the same reasons: the rows go when the
 * account does, so writes are serialized against account transitions through [AccountSession],
 * while reads are not — a library refresh can hold that lock for seconds, and the player must
 * never wait on it to learn where to resume.
 */
@Singleton
class RoomEpisodeProgressRepository @Inject constructor(
    private val dao: EpisodeProgressDao,
    private val session: AccountSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : EpisodeProgressRepository {

    override fun observe(animeId: Int): Flow<List<EpisodeProgress>> = dao.observeByAnime(animeId)
        .map { rows -> rows.map { it.toDomain() } }
        // Room invalidates per table: without this, every other anime's progress sample would
        // wake up whoever is watching this one.
        .distinctUntilChanged()

    override fun observeAll(): Flow<List<EpisodeProgress>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun save(progress: EpisodeProgress) = accountWrite { dao.upsert(progress.toEntity()) }

    override suspend fun clear(animeId: Int) = accountWrite { dao.deleteByAnime(animeId) }

    private suspend fun accountWrite(block: suspend () -> Unit) = try {
        session.withAccount { withContext(io) { block() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: AccountSessionChanged) {
        // The account guard already speaks the domain's language.
        throw rejected
    } catch (error: Exception) {
        // Nothing above the data layer knows SQLite, and the copy for a failed disk write is not
        // the copy for a failed request, so Room's exceptions are wrapped, never passed on.
        throw StorageFailure(error)
    }
}
