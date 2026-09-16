package app.kaeru.ui.common.player

import androidx.media3.common.Player
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.together.LocalAction
import app.kaeru.player.ActionOrigin
import app.kaeru.player.PlaybackController
import app.kaeru.player.PlaybackEngine
import app.kaeru.player.PlaybackEvent
import app.kaeru.player.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records what the screen asked for and lets the test say what playback is doing back. */
class FakePlaybackController : PlaybackController {
    val playback = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = playback

    val announced = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: Flow<PlaybackEvent> = announced

    override val videoPlayer: StateFlow<Player?> = MutableStateFlow(null)

    val announcedActions = MutableSharedFlow<LocalAction>(extraBufferCapacity = 8)
    override val localActions: Flow<LocalAction> = announcedActions

    val played = mutableListOf<PlaybackTarget>()
    val seeks = mutableListOf<Long>()
    val tracks = mutableListOf<Translation>()
    val qualities = mutableListOf<Quality>()
    val switches = mutableListOf<Pair<PlaybackEngine, Long>>()
    var toggles = 0
        private set
    var nexts = 0
        private set
    var cancels = 0
        private set
    var retries = 0
        private set
    var reports = 0
        private set
    var attaches = 0
        private set
    var releases = 0
        private set

    /** Every `setPlaying`, in order, as the value it asked for. */
    val playPauses = mutableListOf<Boolean>()
    var rate = 1.0f
        private set

    override suspend fun play(target: PlaybackTarget, origin: ActionOrigin) {
        played += target
        playback.value = PlaybackState(target = target, isBuffering = true, positionMs = target.startPositionMs)
    }

    override fun togglePlayPause() {
        toggles += 1
    }

    override fun setPlaying(playing: Boolean, origin: ActionOrigin) {
        playPauses += playing
    }

    override fun seekTo(positionMs: Long, origin: ActionOrigin) {
        seeks += positionMs
    }

    override fun setRate(factor: Float) {
        rate = factor
    }

    override fun seekBy(deltaMs: Long) = seekTo(playback.value.positionMs + deltaMs)

    override suspend fun changeTranslation(translation: Translation) {
        tracks += translation
    }

    override fun changeQuality(quality: Quality) {
        qualities += quality
    }

    override suspend fun playNext() {
        nexts += 1
    }

    override suspend fun switchEngine(engine: PlaybackEngine, carryPositionMs: Long) {
        switches += engine to carryPositionMs
        playback.value = playback.value.copy(positionMs = carryPositionMs)
    }

    override fun cancelAutoplay() {
        cancels += 1
    }

    override suspend fun retry() {
        retries += 1
    }

    override fun attachScreen() {
        attaches += 1
    }

    override fun reportProgress() {
        reports += 1
    }

    override fun release() {
        releases += 1
    }
}
