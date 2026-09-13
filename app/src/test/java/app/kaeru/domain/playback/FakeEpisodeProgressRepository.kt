package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.repository.EpisodeProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory stand-in for the Room repository. Single-threaded by contract: every test that uses it
 * runs on one test dispatcher, so the plain list below needs no synchronization.
 */
class FakeEpisodeProgressRepository : EpisodeProgressRepository {
    private val rows = MutableStateFlow<Map<Pair<Int, Int>, EpisodeProgress>>(emptyMap())

    /** Saves that ran to completion, in order. */
    val saved = mutableListOf<EpisodeProgress>()
    val cleared = mutableListOf<Int>()

    /** Thrown by [save], to stand in for a logout or a disk failure. */
    var failSaveWith: Throwable? = null

    fun seed(progress: EpisodeProgress) = rows.update { it + (progress.key() to progress) }

    override fun observe(animeId: Int): Flow<List<EpisodeProgress>> =
        rows.map { all -> all.values.filter { it.animeId == animeId }.sortedBy { it.episode } }

    override fun observeAll(): Flow<List<EpisodeProgress>> = rows.map { it.values.toList() }

    override suspend fun save(progress: EpisodeProgress) {
        failSaveWith?.let { throw it }
        saved += progress
        rows.update { it + (progress.key() to progress) }
    }

    override suspend fun clear(animeId: Int) {
        cleared += animeId
        rows.update { all -> all.filterKeys { it.first != animeId } }
    }

    private fun EpisodeProgress.key() = animeId to episode
}
