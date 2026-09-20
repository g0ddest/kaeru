package app.kaeru.data.library

import androidx.room.withTransaction
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.EpisodeProgressDao
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.mergeShort
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomain
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOpKind
import app.kaeru.domain.sync.RateOutboxRepository
import app.kaeru.domain.sync.ReplayRequest
import app.kaeru.shared.data.network.NetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many titles one refresh may fetch full details for.
 *
 * A ceiling on what a single sync costs, not a rule about which titles matter. The list itself
 * already carries everything a screen needs to draw a card and everything the new-episode check
 * reads; details are the description, the screenshots and the airing date, and a title that waits
 * one more sync for those loses nothing the viewer can see.
 */
private const val DETAILS_PER_REFRESH = 25

/** Room is the observable source of truth; network failures are returned without clearing it. */
@Singleton
class ShikimoriLibraryRepository @Inject constructor(
    private val api: ShikimoriApi,
    private val db: KaeruDatabase,
    private val animeDao: AnimeDao,
    private val userRateDao: UserRateDao,
    private val watchStateDao: WatchStateDao,
    private val episodeProgressDao: EpisodeProgressDao,
    private val prefs: AppPreferences,
    private val session: AccountSession,
    private val posters: PosterEnricher,
    private val outbox: RateOutboxRepository,
    private val syncer: OutboxSyncer,
    private val replays: ReplayRequest,
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
    override suspend fun refresh(): Result<Unit> {
        // Outside the account lock, and before it is taken. Anything still queued goes out before
        // the server is asked what it holds, so a mark made on a train is part of the answer
        // rather than something the answer has to be defended against — and a drain that reached
        // back into this repository from inside the lock would suspend on it forever.
        withContext(io) { syncer.replay() }
        return accountWrite { userId -> fetchLibrary(userId) }
    }

    /**
     * A title Shikimori refused needs no separate re-read: its op is gone, so it is no longer
     * pending, and the list this fetches is the server's truth for it like any other.
     */
    private suspend fun fetchLibrary(userId: Long) {
        // Read before the list is asked for as well as after. An op that drains while the list is
        // in flight is no longer pending by the time the merge runs, and the list in hand was
        // written before the value that drain produced.
        val pendingBefore = outbox.pendingAnimeIds()
        val rates = api.libraryRates(userId).map { it.toDomain() }
        val ids = rates.map { it.animeId }.distinct()
        // Fifty at a time for SQLite's sake; the network side batches itself the same way.
        val cached = ids.chunked(50).flatMap { animeDao.getByIds(it) }.associateBy { it.id }
        val fresh = api.animesByIds(ids).map { it.toDomain() }.withRealPosters()
        animeDao.upsertAll(fresh.map { anime ->
            cached[anime.id]?.mergeShort(anime) ?: anime.toEntity(detailsFetchedAt = null)
        })
        // A title with a write still waiting keeps the local rate: the server's copy of it is
        // behind the viewer by exactly the marks that have not left the device yet, and merging it
        // would put an episode they have already ticked off back in front of them.
        //
        // In one transaction because a replay running on the application scope writes the same two
        // tables: read the queue and the rows it protects apart from the replacement, and the
        // replacement can put back a rate that has since been sent.
        db.withTransaction {
            val pending = pendingBefore + outbox.pendingAnimeIds()
            val unsent = pending.mapNotNull { userRateDao.getByAnimeId(it) }
            userRateDao.replaceAll(rates.filterNot { it.animeId in pending }.map { it.toEntity() } + unsent)
        }

        val watchingIds = rates.filter {
            it.status == ListStatus.WATCHING || it.status == ListStatus.REWATCHING
        }.map { it.animeId }.toSet()
        val staleBefore = clock.instant().minus(detailsTtl)
        // Two requests each, so a list of eighty ongoing titles would be a hundred and sixty calls
        // on top of the list itself — four times a day from a background job the viewer cannot see,
        // and enough to make Shikimori start refusing. Capped, oldest first, so every title still
        // comes round: the ones that have waited longest go first and the rest catch up on the
        // next run. Never fetched at all sorts ahead of everything, which is what a new title
        // deserves.
        fresh.filter { it.id in watchingIds && it.status == AnimeStatus.ONGOING }
            .filter { cached[it.id]?.detailsFetchedAt?.isAfter(staleBefore) != true }
            .sortedBy { cached[it.id]?.detailsFetchedAt ?: Instant.EPOCH }
            .take(DETAILS_PER_REFRESH)
            .forEach { fetchDetails(it.id) }
        prefs.setLastFullSync(clock.instant())
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
        if (existing != null && existing.mustQueue()) {
            queue(existing.copy(status = status), RateOpKind.STATUS, status.apiValue)
            return@accountWrite
        }
        // Resolve the card first: a remote create must not succeed with no displayable anime.
        val missingAnime = if (animeDao.getById(animeId) == null) {
            api.animesByIds(listOf(animeId)).firstOrNull { it.id == animeId }
                ?.toDomain()?.toEntity(detailsFetchedAt = null)
                ?: error("No anime $animeId returned by Shikimori")
        } else null
        val dto = try {
            if (existing == null) {
                api.createUserRate(userId, animeId, status.apiValue)
            } else {
                api.updateUserRate(existing.id, status = status.apiValue)
            }
        } catch (offline: NetworkException) {
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
        if (existing.mustQueue()) {
            queue(existing.copy(episodes = episodes), RateOpKind.EPISODES, episodes.toString())
            return@accountWrite
        }
        val dto = try {
            api.updateUserRate(existing.id, episodes = episodes)
        } catch (offline: NetworkException) {
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
    /**
     * Whether this write has to go through the queue rather than over the network.
     *
     * Two reasons, and both are about a request that would be wrong rather than one that would
     * fail. A rate Shikimori has never been told about has no id to send anything to, and the id
     * standing in for it is not one: PATCHing it would 404, and a 404 is not the failure that
     * queues. And a title with a write still waiting must not be overtaken — the drain would send
     * the older value afterwards, and Shikimori and the device would both end up on the mark the
     * viewer replaced. Queued, it is the newest value per kind that the drain sends.
     */
    private suspend fun UserRateEntity.mustQueue(): Boolean = id < 0 || outbox.hasPendingFor(animeId)

    private fun UserRateEntity?.orNewRate(animeId: Int): UserRateEntity = this
        ?: UserRateEntity(-animeId.toLong(), animeId, ListStatus.PLANNED, episodes = 0, updatedAt = clock.instant())

    /**
     * Applies a write the network would not take, and remembers to send it.
     *
     * The viewer is told it worked, because as far as their library is concerned it did: the row
     * they are looking at now says what they set, and the queue is what makes that true on
     * Shikimori as well. Both happen under the account lock the write already holds.
     *
     * One transaction, because half of this is a lie: a changed rate with nothing queued behind it
     * is a mark that will never be sent and that the next refresh quietly reverts.
     */
    private suspend fun queue(local: UserRateEntity, kind: RateOpKind, value: String) {
        db.withTransaction {
            userRateDao.upsertAll(listOf(local.copy(updatedAt = clock.instant())))
            outbox.enqueue(local.animeId, kind, value)
        }
        // After the transaction, never inside it: a drain that started before the commit would
        // find nothing. Fire and forget, and a no-op while there is no network to drain into.
        replays.requestReplay()
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
