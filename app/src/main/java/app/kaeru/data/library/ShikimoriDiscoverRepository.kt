package app.kaeru.data.library

import app.kaeru.data.shikimori.AnimeShortDto
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomain
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.discover.Season
import app.kaeru.domain.model.Anime
import app.kaeru.domain.repository.DiscoverRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Shikimori's filter for titles currently on air. */
private const val ONGOING = "ongoing"

/** Enough to fill a row and a good scroll past it, and one request against the rate limit. */
private const val ROW_LIMIT = 20

/** The cache key for the one row that is not about a season. */
private const val NOW_KEY = "now"

/**
 * The two discovery rows, read from Shikimori's catalogue and kept in memory for six hours.
 *
 * **Why six hours and why memory.** Neither row is about the viewer, so neither is worth a table:
 * what is popular does not change between breakfast and dinner, and a season's list does not
 * change at all once it has started. Six hours means a viewer who opens the app twice in an
 * evening pays for one request, which matters against a 5-per-second / 90-per-minute budget shared
 * with the sync that actually keeps their list correct. Anything the process forgets on a cold
 * start is one cheap request to get back.
 *
 * **What is cached is the answer, not the attempt.** A failed read stores nothing, so the next
 * pull tries again rather than serving a remembered silence for six hours.
 *
 * The network happens outside the lock. The two rows load at once on a cold home screen, and a
 * mutex held across a request would make the second row wait for the first for no reason; the
 * worst a race can do is fetch the same season twice and store the same answer twice.
 */
@Singleton
class ShikimoriDiscoverRepository @Inject constructor(
    private val api: ShikimoriApi,
    private val posters: PosterEnricher,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : DiscoverRepository {
    private val ttl: Duration = Duration.ofHours(6)
    private val lock = Mutex()
    private val cached = mutableMapOf<String, Cached>()

    private class Cached(val titles: List<Anime>, val at: Instant)

    override suspend fun popularNow(force: Boolean): Result<List<Anime>> =
        read(NOW_KEY, force) { api.animes(status = ONGOING, limit = ROW_LIMIT) }

    override suspend fun seasonal(season: Season, force: Boolean): Result<List<Anime>> =
        read(season.apiValue, force) { api.animes(season = season.apiValue, limit = ROW_LIMIT) }

    private suspend fun read(
        key: String,
        force: Boolean,
        fetch: suspend () -> List<AnimeShortDto>,
    ): Result<List<Anime>> {
        if (!force) fresh(key)?.let { return Result.success(it) }
        return withContext(io) {
            try {
                val titles = posters.enrich(fetch().map { it.toDomain() })
                lock.withLock { cached[key] = Cached(titles, clock.instant()) }
                Result.success(titles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error.toDomainFailure())
            }
        }
    }

    private suspend fun fresh(key: String): List<Anime>? {
        val staleBefore = clock.instant().minus(ttl)
        return lock.withLock { cached[key]?.takeIf { it.at.isAfter(staleBefore) }?.titles }
    }
}
