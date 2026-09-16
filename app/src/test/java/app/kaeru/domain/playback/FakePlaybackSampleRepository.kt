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

    /** Which anime and episode each [forgetFrom] named, in order. */
    val forgotten = mutableListOf<Pair<Int, Int>>()

    override suspend fun save(watch: WatchState, progress: EpisodeProgress) {
        failSaveWith?.let { throw it }
        episodes.write(progress)
        watchStates.save(watch)
    }

    override suspend fun forgetFrom(animeId: Int, episode: Int, at: Instant) {
        failForgetWith?.let { throw it }
        forgotten += animeId to episode
        episodes.dropFrom(animeId, episode)
        watchStates.rewindFrom(animeId, episode, at)
    }
}
