package app.kaeru.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A player that decodes nothing and does exactly what the test says, in the order it says it.
 *
 * Real playback only ever reaches the controller as [EngineState] snapshots, so a fake that
 * emits those snapshots exercises the controller's whole state machine without a device.
 */
class FakePlaybackEngine : PlaybackEngine {
    data class Prepared(
        val url: String,
        val headers: StreamHeaders,
        val startPositionMs: Long,
        val metadata: StreamMetadata?,
    )

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    /** Nothing to render: this engine never builds a player. */
    override val videoPlayer: StateFlow<Player?> = MutableStateFlow(null)

    /** Every source the controller pointed the engine at, in order. */
    val prepared = mutableListOf<Prepared>()
    var releases = 0
        private set

    override fun prepare(url: String, headers: StreamHeaders, startPositionMs: Long, metadata: StreamMetadata?) {
        prepared += Prepared(url, headers, startPositionMs, metadata)
        _state.value = EngineState(isBuffering = true, positionMs = startPositionMs)
    }

    override fun play() = _state.update { it.copy(isPlaying = true) }

    override fun pause() = _state.update { it.copy(isPlaying = false) }

    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs, ended = false) }

    /** The speed the last correction asked for. Nothing else in the app ever moves it off 1.0. */
    var rate = 1.0f
        private set

    override fun setRate(factor: Float) {
        rate = factor
    }

    /** Every time the picture was turned down or back up, in order. */
    val ducks = mutableListOf<Boolean>()

    override fun duck(on: Boolean) {
        ducks += on
    }

    override fun release() {
        releases += 1
        _state.value = EngineState()
    }

    /** The manifest was read: the episode now has a length and pictures are coming out. */
    fun ready(durationMs: Long) = _state.update {
        it.copy(isBuffering = false, durationMs = durationMs, isPlaying = true)
    }

    /** Playback moved on, the way a poll of a real player would report it. */
    fun moveTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }

    fun end() = _state.update { it.copy(ended = true, isPlaying = false, positionMs = it.durationMs) }

    fun fail(error: Throwable) = _state.update { it.copy(error = error, isPlaying = false, isBuffering = false) }
}
