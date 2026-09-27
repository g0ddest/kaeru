package app.kaeru.data.viewsync

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.domain.viewsync.LocalViewing
import app.kaeru.domain.viewsync.SyncedViewing
import app.kaeru.shared.domain.sync.RememberedDub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.time.Clock
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
    /**
     * Fetches the card of a title another device made «украдкой»: without it the title has
     * nothing to be drawn with. Null in tests that do not care.
     */
    private val cards: SecretCards? = null,
    private val clock: Clock = Clock.systemUTC(),
) : LocalViewing {

    override suspend fun positions(): List<EpisodeProgress> = withContext(io) {
        database.episodeProgressDao().all().map { it.toDomain() }
    }

    override suspend fun dubs(): Map<Int, RememberedDub> = withContext(io) {
        database.watchStateDao().all()
            .mapNotNull { row -> row.translationId?.let { row.animeId to RememberedDub(it, row.translationTitle) } }
            .toMap()
    }

    override suspend fun secrets(): Map<Int, SecretTitle> = withContext(io) {
        database.secretTitleDao().all().associate { it.animeId to it.toDomain() }
    }

    override suspend fun announcedEpisodes(): Map<Int, Int> = withContext(io) {
        val ids = database.secretTitleDao().all().filter { it.isOn }.map { it.animeId }
        ids.chunked(CARDS_PER_QUERY).flatMap { database.animeDao().getByIds(it) }
            .filter { it.episodes > 0 }
            .associate { it.id to it.episodes }
    }

    override fun statuses(): Flow<Map<Int, ListStatus>> = combine(
        database.userRateDao().observeAll(),
        database.secretTitleDao().observeAll(),
        database.animeDao().observeAll(),
    ) { rates, secrets, animes ->
        val statuses = rates.associate { it.animeId to it.status }.toMutableMap()
        val animeById = animes.associateBy { it.id }
        for (secret in secrets) {
            if (!secret.isOn) continue
            val anime = animeById[secret.animeId]?.toDomain()
            val done = anime != null && SecretTitle.finished(anime, secret.watched, clock.instant())
            statuses[secret.animeId] = if (done) ListStatus.COMPLETED else ListStatus.SECRET
        }
        statuses.toMap()
    }.distinctUntilChanged()

    override suspend fun apply(account: Long, change: SyncedViewing): Boolean {
        val written = write(account, change)
        if (written) fetchMissingCards(change.secrets.filter { it.on }.map { it.animeId })
        return written
    }

    /** Best effort: a card that cannot be fetched now is fetched by the next list refresh. */
    private suspend fun fetchMissingCards(ids: List<Int>) {
        val fetcher = cards ?: return
        for (animeId in ids) {
            try {
                if (withContext(io) { database.animeDao().getById(animeId) } == null) fetcher.fetch(animeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // See above.
            }
        }
    }

    private suspend fun write(account: Long, change: SyncedViewing): Boolean = try {
        session.withAccount { id ->
            if (id != account) return@withAccount false
            withContext(io) {
                database.applySynced(
                    tombstones = change.tombstones,
                    positions = change.positions.map { it.toEntity() },
                    dubs = change.dubs.mapValues { (_, dub) -> dub.id to dub.title },
                    dubEpisodes = change.positions.groupBy { it.animeId }
                        .mapValues { (_, rows) -> rows.maxOf { it.episode } },
                    secrets = change.secrets.map { it.toEntity() },
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A sign-out mid-write, or a disk that refused: nothing written, and the next read tries again.
        false
    }

    private companion object {
        /** Well under SQLite's limit on the ids one `IN (…)` may bind. */
        const val CARDS_PER_QUERY = 500
    }
}

/** Fetches and caches one anime card, for a title that turned «украдкой» elsewhere. */
fun interface SecretCards {
    suspend fun fetch(animeId: Int)
}
