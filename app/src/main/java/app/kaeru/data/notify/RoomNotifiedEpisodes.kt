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

/** What the check remembers, in the one table whose primary key is the deduplication rule. */
@Singleton
class RoomNotifiedEpisodes @Inject constructor(
    private val dao: NotifiedEpisodeDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : NotifiedEpisodes {

    override suspend fun all(): List<NotifiedEpisode> = withContext(io) { dao.getAll().map { it.toDomain() } }

    override suspend fun record(episodes: List<NotifiedEpisode>, at: Instant) {
        if (episodes.isEmpty()) return
        withContext(io) { dao.recordAll(episodes.map { it.toEntity(at) }) }
    }
}
