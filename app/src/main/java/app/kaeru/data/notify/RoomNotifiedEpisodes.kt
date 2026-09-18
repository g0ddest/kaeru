package app.kaeru.data.notify

import app.kaeru.data.local.NotifiedEpisodeDao
import app.kaeru.data.local.toEntity
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.notify.NotifiedEpisode
import app.kaeru.domain.notify.NotifiedEpisodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many rows one title keeps.
 *
 * Far more than the rule can read — it only ever asks about the highest episode and about the one
 * pair it is offering — and deliberately so: fifty is about two seasons of a weekly show, which
 * makes the bound invisible to anything anybody would notice, while still turning a table that
 * grew without limit into one that cannot.
 */
private const val KEEP_PER_ANIME = 50

/** SQLite's own ceiling on bound parameters, with room to spare. */
private const val BINDINGS = 500

/** What the check remembers, in the one table whose primary key is the deduplication rule. */
@Singleton
class RoomNotifiedEpisodes @Inject constructor(
    private val dao: NotifiedEpisodeDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : NotifiedEpisodes {

    override suspend fun forAnime(animeIds: List<Int>): List<NotifiedEpisode> {
        if (animeIds.isEmpty()) return emptyList()
        // Chunked because SQLite counts bound parameters and stops at 999, and a list of a
        // thousand titles is a large one but not an impossible one.
        return withContext(io) {
            animeIds.chunked(BINDINGS).flatMap { dao.getForAnime(it) }.map { it.toDomain() }
        }
    }

    override suspend fun record(episodes: List<NotifiedEpisode>, at: Instant) {
        if (episodes.isEmpty()) return
        withContext(io) {
            dao.recordAll(episodes.map { it.toEntity(at) })
            // Pruned as it is written, which is the only moment a title can have grown. Not in a
            // transaction with the write: a process that dies between the two leaves a few rows
            // too many, and the next check about that title takes them away.
            episodes.map { it.animeId }.distinct().forEach { dao.prune(it, KEEP_PER_ANIME) }
        }
    }

    override suspend fun forget() = withContext(io) { dao.deleteAll() }
}
