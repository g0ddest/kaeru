package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.PlaybackSampleRepository
import java.time.Instant

/**
 * Both rows of a sample, written the way a transaction writes them: both, or neither.
 *
 * Backed by the two fakes the rest of the tests already read, so a sample written through here
 * shows up in their `saved` lists and in what they observe.
 */
class FakePlaybackSampleRepository(
    val watchStates: FakeWatchStateRepository = FakeWatchStateRepository(),
    val episodes: FakeEpisodeProgressRepository = FakeEpisodeProgressRepository(),
) : PlaybackSampleRepository {

    /** Thrown before either row is touched, standing in for a transaction that could not commit. */
    var failSaveWith: Throwable? = null

    /** The same, for the transaction that takes rows away. */
    var failForgetWith: Throwable? = null

    /** And for the one that puts them back. */
    var failRestoreWith: Throwable? = null

    /** Which anime and episode each [forgetFrom] named, in order. */
    val forgotten = mutableListOf<Pair<Int, Int>>()

    override suspend fun save(watch: WatchState, progress: EpisodeProgress) {
        failSaveWith?.let { throw it }
        episodes.write(progress)
        watchStates.save(watch)
    }

    /** Rows put back through [restore], in the order they arrived. */
    val restored = mutableListOf<EpisodeProgress>()

    override suspend fun restore(progress: List<EpisodeProgress>) {
        failRestoreWith?.let { throw it }
        restored += progress
        progress.forEach { episodes.seed(it) }
    }

    override suspend fun forgetFrom(animeId: Int, episode: Int, at: Instant) {
        failForgetWith?.let { throw it }
        forgotten += animeId to episode
        episodes.dropFrom(animeId, episode)
        watchStates.rewindFrom(animeId, episode, at)
    }
}
