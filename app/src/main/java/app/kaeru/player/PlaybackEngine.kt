package app.kaeru.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * What the controller knows about the thing actually decoding video: a flat snapshot,
 * pushed rather than polled.
 *
 * [durationMs] is 0 until the manifest is read. Nothing downstream may treat that as
 * "a zero-length episode": a position written against an unknown duration would look
 * like a finished episode on the next launch.
 */
data class EngineState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: Throwable? = null,
)

/**
 * The seam between playback logic and Media3.
 *
 * Everything the controller decides — when to save a position, when an episode counts as
 * watched, when the next one starts — is written against this interface, so it can be
 * tested against a fake engine instead of a device with a codec.
 */
interface PlaybackEngine {
    val state: StateFlow<EngineState>

    /** The Media3 player to attach a surface to, or null for an engine that renders nothing. */
    val videoPlayer: Player?

    /**
     * Points the engine at one manifest and seeks to [startPositionMs] before the first frame.
     *
     * @param metadata what a notification or a lock screen should say this is; null while
     *   nothing outside the app is showing it.
     */
    fun prepare(url: String, headers: StreamHeaders, startPositionMs: Long, metadata: StreamMetadata? = null)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Stops playback and frees the decoder. The engine stays usable: [prepare] starts it again. */
    fun release()
}
