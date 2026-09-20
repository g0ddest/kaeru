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
 *
 * [write] is not part of the interface, which reads only. It is how [FakePlaybackSampleRepository]
 * puts the episode half of a sample in, and how a test says «this row was already there».
 */
class FakeEpisodeProgressRepository : EpisodeProgressRepository {
    private val rows = MutableStateFlow<Map<Pair<Int, Int>, EpisodeProgress>>(emptyMap())

    /** Rows written through this fake, in order. */
    val saved = mutableListOf<EpisodeProgress>()

    fun seed(progress: EpisodeProgress) = rows.update { it + (progress.key() to progress) }

    override fun observe(animeId: Int): Flow<List<EpisodeProgress>> =
        rows.map { all -> all.values.filter { it.animeId == animeId }.sortedBy { it.episode } }

    fun write(progress: EpisodeProgress) {
        saved += progress
        rows.update { it + (progress.key() to progress) }
    }

    /** What [FakePlaybackSampleRepository.forgetFrom] takes away: this episode and everything after it. */
    fun dropFrom(animeId: Int, episode: Int) = rows.update { all ->
        all.filterNot { (key, _) -> key.first == animeId && key.second >= episode }
    }

    private fun EpisodeProgress.key() = animeId to episode
}
