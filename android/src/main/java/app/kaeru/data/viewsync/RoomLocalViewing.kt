package app.kaeru.data.viewsync

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.viewsync.LocalViewing
import app.kaeru.domain.viewsync.RememberedDub
import app.kaeru.domain.viewsync.SyncedViewing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Positions and dubs in Room, as viewing sync reads and writes them.
 *
 * Writes go around the playback path on purpose — straight to the tables, not through
 * [app.kaeru.domain.playback.WatchProgress] or the rate repository — so a position from another
 * device is never marked on Shikimori and never comes back to sync as this device's own change.
 * They still take the account lock, and only for the account sync read them for: a sign-out or a
 * switch landing meanwhile turns the write down rather than handing one account's rows to another.
 */
@Singleton
class RoomLocalViewing @Inject constructor(
    private val database: KaeruDatabase,
    private val session: AccountSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : LocalViewing {

    override suspend fun positions(): List<EpisodeProgress> = withContext(io) {
        database.episodeProgressDao().all().map { it.toDomain() }
    }

    override suspend fun dubs(): Map<Int, RememberedDub> = withContext(io) {
        database.watchStateDao().all()
            .mapNotNull { row -> row.translationId?.let { row.animeId to RememberedDub(it, row.translationTitle) } }
            .toMap()
    }

    override fun statuses(): Flow<Map<Int, ListStatus>> = database.userRateDao().observeAll()
        .map { rows -> rows.associate { it.animeId to it.status } }
        .distinctUntilChanged()

    override suspend fun apply(account: Long, change: SyncedViewing): Boolean = try {
        session.withAccount { id ->
            if (id != account) return@withAccount false
            withContext(io) {
                database.applySynced(
                    tombstones = change.tombstones,
                    positions = change.positions.map { it.toEntity() },
                    dubs = change.dubs.mapValues { (_, dub) -> dub.id to dub.title },
                    dubEpisodes = change.positions.groupBy { it.animeId }
                        .mapValues { (_, rows) -> rows.maxOf { it.episode } },
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A sign-out mid-write, or a disk that refused: nothing written, and the next read tries again.
        false
    }
}
