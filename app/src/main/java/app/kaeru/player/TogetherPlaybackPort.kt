package app.kaeru.player

import app.kaeru.di.PlaybackScope
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Translation
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.together.LocalAction
import app.kaeru.domain.together.PlaybackPort
import app.kaeru.domain.together.PortState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player, seen through the narrow slot a shared session is given.
 *
 * Every call here is tagged [ActionOrigin.REMOTE], because every call here is a friend's doing —
 * that tag is what keeps it out of [PlaybackController.localActions] and stops two phones
 * announcing the same pause to each other until one of them gives up.
 */
@Singleton
class TogetherPlaybackPort @Inject constructor(
    private val controller: PlaybackController,
    private val resolve: ResolveEpisodeStream,
    @param:PlaybackScope private val scope: CoroutineScope,
) : PlaybackPort {

    override val state: StateFlow<PortState> = controller.state
        .map { playback ->
            PortState(
                positionMs = playback.positionMs,
                playing = playback.isPlaying,
                buffering = playback.isBuffering,
                animeId = playback.target?.animeId,
                episode = playback.target?.episode,
                // What is playing, and only then what was asked for: a track the source would not
                // serve is not the one the friend should be told this phone is listening to.
                translationId = playback.stream?.translation?.id ?: playback.target?.translation?.id,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, PortState())

    override val localActions: Flow<LocalAction> get() = controller.localActions

    override suspend fun play() = controller.setPlaying(true, ActionOrigin.REMOTE)

    override suspend fun pause() = controller.setPlaying(false, ActionOrigin.REMOTE)

    override suspend fun seekTo(positionMs: Long) = controller.seekTo(positionMs, ActionOrigin.REMOTE)

    /**
     * Anything but a receiver across the room. media3's Cast player carries no speed command, so
     * the rung of the ladder below a seek is not available while the picture is over there.
     */
    override val supportsRate: Boolean get() = !controller.state.value.isCasting

    override suspend fun setRate(factor: Float) = controller.setRate(factor)

    override fun duck(on: Boolean) = controller.duck(on)

    override suspend fun openEpisode(animeId: Int, episode: Int, translationId: Int?, positionMs: Long) {
        controller.play(
            PlaybackTarget(animeId, episode, positionMs, translation = trackFor(animeId, translationId)),
            ActionOrigin.REMOTE,
        )
    }

    /**
     * The friend's voice as a track this device can ask for, or nothing.
     *
     * Nothing is the interesting case and it is not a failure: an anime whose Kodik page lists a
     * studio one phone has and the other does not is ordinary, and the answer is that this side
     * plays what it can. Which it did is visible in [state], and saying so is the session's job.
     *
     * The catalogue is only asked when the answer is not already on screen, which is most of the
     * time — an episode change keeps the voice it was playing in.
     */
    private suspend fun trackFor(animeId: Int, translationId: Int?): Translation? {
        if (translationId == null) return null
        val playing = controller.state.value.stream?.translation
        if (playing?.id == translationId) return playing
        return resolve.translations(animeId).getOrNull()
            ?.firstOrNull { it.translation.id == translationId }
            ?.translation
    }
}
