package app.kaeru.data.library

import app.kaeru.data.local.RateOutboxDao
import app.kaeru.data.local.RateOutboxEntity
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import app.kaeru.domain.sync.RateOutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline write queue in Room.
 *
 * No account lock on the way in: [enqueue] is called from inside a rate write that already holds
 * it, and a refresh reading [observePendingAnimeIds] must not wait on one. The rows are
 * account-owned all the same — they go with the account, in `KaeruDatabase.clearAccountData`.
 */
@Singleton
class RoomRateOutboxRepository @Inject constructor(
    private val dao: RateOutboxDao,
    private val clock: Clock,
) : RateOutboxRepository {

    override fun observeAll(): Flow<List<RateOp>> = dao.observeAll()
        .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
        .distinctUntilChanged()

    override fun observePendingAnimeIds(): Flow<Set<Int>> = dao.observePendingAnimeIds()
        .map { it.toSet() }
        // Draining one of several queued writes for the same anime leaves the set unchanged, and
        // a refresh has no reason to start over because of it.
        .distinctUntilChanged()

    override suspend fun pendingAnimeIds(): Set<Int> = dao.pendingAnimeIds().toSet()

    override suspend fun hasPendingFor(animeId: Int): Boolean = dao.hasPendingFor(animeId)

    override suspend fun enqueue(animeId: Int, kind: RateOpKind, value: String): Long =
        dao.insert(RateOutboxEntity(animeId = animeId, kind = kind.name, value = value, createdAt = clock.instant()))

    override suspend fun remove(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}
