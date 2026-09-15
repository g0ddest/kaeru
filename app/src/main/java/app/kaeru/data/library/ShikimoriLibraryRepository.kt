package app.kaeru.data.library

import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.EpisodeProgressDao
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.mergeShort
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserRatePayload
import app.kaeru.data.shikimori.UserRateRequest
import app.kaeru.data.shikimori.toDomain
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOpKind
import app.kaeru.domain.sync.RateOutboxRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Room is the observable source of truth; network failures are returned without clearing it. */
@Singleton
class ShikimoriLibraryRepository @Inject constructor(
    private val api: ShikimoriApi,
    private val animeDao: AnimeDao,
    private val userRateDao: UserRateDao,
    private val watchStateDao: WatchStateDao,
    private val episodeProgressDao: EpisodeProgressDao,
    private val prefs: AppPreferences,
    private val session: AccountSession,
    private val posters: PosterEnricher,
    private val outbox: RateOutboxRepository,
    // Lazily, because the syncer needs this repository back for the refresh a rejected write asks
    // for. A Provider is the one link in that circle Hilt can build.
    private val syncer: Provider<OutboxSyncer>,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : LibraryRepository {
    private val detailsTtl = Duration.ofHours(6)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLibrary(): Flow<List<LibraryEntry>> = session.observations.flatMapLatest { observation ->
        val entries = if (observation.userId == null) flowOf(emptyList()) else observeAccountLibrary()
        entries.map { observation to it }
    }.buffer(0).transform { (observation, entries) ->
        // Validate after the rendezvous channel and linearize caller entry with all session/token
        // changes. The monitor is released when caller code first suspends, so slow collectors
        // do not keep transitions locked while awaiting their next step.
        session.emitIfCurrent(observation) { emit(entries) }
    }

    private fun observeAccountLibrary(): Flow<List<LibraryEntry>> = combine(
        animeDao.observeAll(), userRateDao.observeAll(), watchStateDao.observeAll(), episodeProgressDao.observeAll(),
    ) { animes, rates, watches, progress ->
        val animeById = animes.associateBy { it.id }
        val watchById = watches.associateBy { it.animeId }
        // Grouped once for the whole library rather than filtered per entry: the table holds a row
        // per episode ever started, so a scan per anime would be the library squared.
        val progressByAnime = progress.groupBy { it.animeId }
        rates.mapNotNull { rate ->
            val anime = animeById[rate.animeId] ?: return@mapNotNull null
            LibraryEntry(
                anime.toDomain(),
                rate.toDomain(),
                watchById[rate.animeId]?.toDomain(),
                progressByAnime[rate.animeId].orEmpty().map { it.toDomain() },
            )
        }
    }

    override fun observeAnime(id: Int): Flow<LibraryEntry?> =
        observeLibrary().map { entries -> entries.firstOrNull { it.anime.id == id } }

    override fun observeAnimeDetails(id: Int): Flow<Anime?> = animeDao.observeById(id).map { it?.toDomain() }

    /**
     * All statuses are synced until the later WorkManager split. A failed list/card fetch leaves
     * rates untouched. A failed detail enrichment retains the refreshed rates and old details,
     * returns failure, and does not advance lastFullSync.
     */
    override suspend fun refresh(): Result<Unit> = accountWrite { userId ->
        // Anything still queued goes out before the server is asked what it holds, so a mark made
        // on a train is part of the answer rather than something the answer has to be defended
        // against. Whatever will not send stays queued and is defended against below.
        syncer.get().replay()
        val rates = ListStatus.entries.flatMap { fetchRates(userId, it) }
        val ids = rates.map { it.animeId }.distinct()
        val cached = ids.chunked(50).flatMap { animeDao.getByIds(it) }.associateBy { it.id }
        val fresh = ids.chunked(50).flatMap { batch ->
            api.animesByIds(batch.joinToString(","), limit = 50).map { it.toDomain() }
        }.withRealPosters()
        animeDao.upsertAll(fresh.map { anime ->
            cached[anime.id]?.mergeShort(anime) ?: anime.toEntity(detailsFetchedAt = null)
        })
        // A title with a write still waiting keeps the local rate: the server's copy of it is
        // behind the viewer by exactly the marks that have not left the device yet, and merging it
        // would put an episode they have already ticked off back in front of them.
        val pending = outbox.observePendingAnimeIds().first()
        val unsent = pending.mapNotNull { userRateDao.getByAnimeId(it) }
        userRateDao.replaceAll(rates.filterNot { it.animeId in pending }.map { it.toEntity() } + unsent)

        val watchingIds = rates.filter {
            it.status == ListStatus.WATCHING || it.status == ListStatus.REWATCHING
        }.map { it.animeId }.toSet()
        val staleBefore = clock.instant().minus(detailsTtl)
        fresh.filter { it.id in watchingIds && it.status == AnimeStatus.ONGOING }
            .filter { cached[it.id]?.detailsFetchedAt?.isAfter(staleBefore) != true }
            .forEach { fetchDetails(it.id) }
        prefs.setLastFullSync(clock.instant())
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

    override suspend fun refreshAnime(id: Int): Result<Unit> = accountWrite {
        fetchDetails(id)
    }

    private suspend fun fetchDetails(id: Int) {
        val dto = api.anime(id)
        // Both requests must succeed before replacing the cached detail row or its freshness.
        val details = listOf(dto.copy(screenshots = api.screenshots(id)).toDomain()).withRealPosters().single()
        animeDao.upsertAll(listOf(details.toEntity(detailsFetchedAt = clock.instant())))
    }

    override suspend fun search(query: String): Result<List<Anime>> = onIo {
        api.search(query).map { it.toDomain() }.withRealPosters()
    }

    /** Shared with the discovery rows, so the same title shows the same artwork everywhere. */
    private suspend fun List<Anime>.withRealPosters(): List<Anime> = posters.enrich(this)

    override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = accountWrite { userId ->
        val existing = userRateDao.getByAnimeId(animeId)
        // Resolve the card first: a remote create must not succeed with no displayable anime.
        val missingAnime = if (animeDao.getById(animeId) == null) {
            api.animesByIds(animeId.toString()).firstOrNull { it.id == animeId }
                ?.toDomain()?.toEntity(detailsFetchedAt = null)
                ?: error("No anime $animeId returned by Shikimori")
        } else null
        val dto = try {
            if (existing == null) {
                api.createUserRate(UserRateRequest(UserRatePayload(
                    userId = userId, targetId = animeId, targetType = "Anime", status = status.apiValue,
                )))
            } else {
                api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(status = status.apiValue)))
            }
        } catch (offline: IOException) {
            // The card came off the network a moment ago; without it the queued rate would name a
            // title the library cannot draw, and the change would be invisible until a refresh.
            if (missingAnime != null) animeDao.upsertAll(listOf(missingAnime))
            queue(existing.orNewRate(animeId).copy(status = status), RateOpKind.STATUS, status.apiValue)
            return@accountWrite
        }
        if (missingAnime != null) animeDao.upsertAll(listOf(missingAnime))
        userRateDao.upsertAll(listOf(dto.toDomain().copy(
            animeId = animeId, status = status, updatedAt = clock.instant(),
        ).toEntity()))
    }

    override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = accountWrite {
        val existing = userRateDao.getByAnimeId(animeId) ?: error("No user_rate for anime $animeId")
        val dto = try {
            api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(episodes = episodes)))
        } catch (offline: IOException) {
            queue(existing.copy(episodes = episodes), RateOpKind.EPISODES, episodes.toString())
            return@accountWrite
        }
        userRateDao.upsertAll(listOf(existing.copy(episodes = dto.episodes, updatedAt = clock.instant())))
    }

    /**
     * A rate this device has but Shikimori has never been told about.
     *
     * The id is the anime's, negated: rate ids from Shikimori are positive, so a negative one can
     * only mean «not created yet», which is exactly what the syncer has to know to send a create
     * rather than an update. The real id arrives with the server's answer and replaces it.
     */
    private fun UserRateEntity?.orNewRate(animeId: Int): UserRateEntity = this
        ?: UserRateEntity(-animeId.toLong(), animeId, ListStatus.PLANNED, episodes = 0, updatedAt = clock.instant())

    /**
     * Applies a write the network would not take, and remembers to send it.
     *
     * The viewer is told it worked, because as far as their library is concerned it did: the row
     * they are looking at now says what they set, and the queue is what makes that true on
     * Shikimori as well. Both happen under the account lock the write already holds.
     */
    private suspend fun queue(local: UserRateEntity, kind: RateOpKind, value: String) {
        userRateDao.upsertAll(listOf(local.copy(updatedAt = clock.instant())))
        outbox.enqueue(local.animeId, kind, value)
    }

    private suspend fun accountWrite(block: suspend (Long) -> Unit): Result<Unit> = try {
        // Capture the session generation before dispatching or waiting for the shared mutex.
        session.withAccount { userId -> withContext(io) { block(userId) } }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toDomainFailure())
    }

    private suspend fun <T> onIo(block: suspend () -> T): Result<T> = withContext(io) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error.toDomainFailure())
        }
    }
}
