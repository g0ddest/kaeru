package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.PlaybackSampleRepository

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

    override suspend fun save(watch: WatchState, progress: EpisodeProgress) {
        failSaveWith?.let { throw it }
        episodes.save(progress)
        watchStates.save(watch)
    }
}
