package app.kaeru.data.library

import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.mergeShort
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserRatePayload
import app.kaeru.data.shikimori.UserRateRequest
import app.kaeru.data.shikimori.toDomain
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Room is the observable source of truth; network failures are returned without clearing it. */
@Singleton
class ShikimoriLibraryRepository @Inject constructor(
    private val api: ShikimoriApi,
    private val animeDao: AnimeDao,
    private val userRateDao: UserRateDao,
    private val watchStateDao: WatchStateDao,
    private val prefs: AppPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : LibraryRepository {
    // Covers refreshes and mutations so a stale refresh cannot overwrite a just-completed patch.
    private val writeLock = Mutex()
    private val detailsTtl = Duration.ofHours(6)

    override fun observeLibrary(): Flow<List<LibraryEntry>> = combine(
        animeDao.observeAll(), userRateDao.observeAll(), watchStateDao.observeAll(),
    ) { animes, rates, watches ->
        val animeById = animes.associateBy { it.id }
        val watchById = watches.associateBy { it.animeId }
        rates.mapNotNull { rate ->
            val anime = animeById[rate.animeId] ?: return@mapNotNull null
            LibraryEntry(anime.toDomain(), rate.toDomain(), watchById[rate.animeId]?.toDomain())
        }
    }

    override fun observeAnime(id: Int): Flow<LibraryEntry?> =
        observeLibrary().map { entries -> entries.firstOrNull { it.anime.id == id } }

    /**
     * All statuses are synced until the later WorkManager split. A failed list/card fetch leaves
     * rates untouched. A failed detail enrichment retains the refreshed rates and old details,
     * returns failure, and does not advance lastFullSync.
     */
    override suspend fun refresh(): Result<Unit> = onIo {
        writeLock.withLock {
            val userId = ensureUserId()
            val rates = ListStatus.entries.flatMap { fetchRates(userId, it) }
            val ids = rates.map { it.animeId }.distinct()
            val cached = ids.chunked(50).flatMap { animeDao.getByIds(it) }.associateBy { it.id }
            val fresh = ids.chunked(50).flatMap { batch ->
                api.animesByIds(batch.joinToString(","), limit = 50).map { it.toDomain() }
            }
            animeDao.upsertAll(fresh.map { anime ->
                cached[anime.id]?.mergeShort(anime) ?: anime.toEntity(detailsFetchedAt = null)
            })
            userRateDao.replaceAll(rates.map { it.toEntity() })

            val watchingIds = rates.filter {
                it.status == ListStatus.WATCHING || it.status == ListStatus.REWATCHING
            }.map { it.animeId }.toSet()
            val staleBefore = clock.instant().minus(detailsTtl)
            fresh.filter { it.id in watchingIds && it.status == AnimeStatus.ONGOING }
                .filter { cached[it.id]?.detailsFetchedAt?.isAfter(staleBefore) != true }
                .forEach { fetchDetails(it.id) }
            prefs.setLastFullSync(clock.instant())
        }
    }

    private suspend fun fetchRates(userId: Long, status: ListStatus): List<UserRate> {
        val rates = mutableListOf<UserRate>()
        var page = 1
        do {
            val batch = api.userRates(userId, status.apiValue, page = page++, limit = 1000)
            rates += batch.map { it.toDomain() }
        } while (batch.size == 1000)
        return rates
    }

    override suspend fun refreshAnime(id: Int): Result<Unit> = onIo {
        writeLock.withLock { fetchDetails(id) }
    }

    private suspend fun fetchDetails(id: Int) {
        val dto = api.anime(id)
        // Both requests must succeed before replacing the cached detail row or its freshness.
        val details = dto.copy(screenshots = api.screenshots(id)).toDomain()
        animeDao.upsertAll(listOf(details.toEntity(detailsFetchedAt = clock.instant())))
    }

    override suspend fun search(query: String): Result<List<Anime>> = onIo {
        api.search(query).map { it.toDomain() }
    }

    override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = onIo {
        writeLock.withLock {
            val existing = userRateDao.getByAnimeId(animeId)
            // Resolve the card first: a remote create must not succeed with no displayable anime.
            val missingAnime = if (animeDao.getById(animeId) == null) {
                api.animesByIds(animeId.toString()).firstOrNull { it.id == animeId }
                    ?.toDomain()?.toEntity(detailsFetchedAt = null)
                    ?: error("No anime $animeId returned by Shikimori")
            } else null
            val dto = if (existing == null) {
                api.createUserRate(UserRateRequest(UserRatePayload(
                    userId = ensureUserId(), targetId = animeId, targetType = "Anime", status = status.apiValue,
                )))
            } else {
                api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(status = status.apiValue)))
            }
            if (missingAnime != null) animeDao.upsertAll(listOf(missingAnime))
            userRateDao.upsertAll(listOf(dto.toDomain().copy(
                animeId = animeId, status = status, updatedAt = clock.instant(),
            ).toEntity()))
        }
    }

    override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = onIo {
        writeLock.withLock {
            val existing = userRateDao.getByAnimeId(animeId) ?: error("No user_rate for anime $animeId")
            val dto = api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(episodes = episodes)))
            userRateDao.upsertAll(listOf(existing.copy(episodes = dto.episodes, updatedAt = clock.instant())))
        }
    }

    private suspend fun ensureUserId(): Long = prefs.userId() ?: api.whoami().id.also { prefs.setUserId(it) }

    private suspend fun <T> onIo(block: suspend () -> T): Result<T> = withContext(io) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
