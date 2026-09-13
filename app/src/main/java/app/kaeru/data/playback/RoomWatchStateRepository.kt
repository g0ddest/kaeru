package app.kaeru.data.playback

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.WatchStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback positions in Room, one row per anime.
 *
 * The rows belong to the signed-in account — logging out drops them with the rest of the
 * account's cache — so writes go through [AccountSession], which serializes them against
 * account transitions and refreshes. Reads deliberately do not: a library refresh can hold
 * that lock for seconds, and the player must never wait on it to learn where to resume.
 */
@Singleton
class RoomWatchStateRepository @Inject constructor(
    private val dao: WatchStateDao,
    private val session: AccountSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : WatchStateRepository {

    override fun observe(animeId: Int): Flow<WatchState?> = dao.observeByAnimeId(animeId)
        .map { it?.toDomain() }
        // Room invalidates per table: without this, every other anime's progress sample
        // would wake up whoever is watching this one.
        .distinctUntilChanged()

    // Like [observe], a read, so it does not wait on the account lock either. The whole table is
    // a few dozen tiny rows — one per anime ever started — and the ranking asks for it once per
    // request rather than once per comparison.
    override fun observeAll(): Flow<List<WatchState>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun save(state: WatchState) = accountWrite { dao.upsert(state.toEntity()) }

    override suspend fun clear(animeId: Int) = accountWrite { dao.deleteByAnimeId(animeId) }

    private suspend fun accountWrite(block: suspend () -> Unit) = try {
        session.withAccount { withContext(io) { block() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: AccountSessionChanged) {
        // The account guard already speaks the domain's language.
        throw rejected
    } catch (error: Exception) {
        // Nothing above the data layer knows SQLite, and the copy for a failed disk write is
        // not the copy for a failed request, so Room's exceptions are wrapped, never passed on.
        throw StorageFailure(error)
    }
}
