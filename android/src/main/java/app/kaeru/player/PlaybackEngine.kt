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
    /** How much of the media is downloaded and ready to play, as a position, not a length. */
    val bufferedPositionMs: Long = 0,
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

    /**
     * The Media3 player to attach a surface to: null before one exists and again after it is
     * given back, so a screen watching this follows the player rather than caching a dead one.
     * An engine that renders nothing never publishes one.
     */
    val videoPlayer: StateFlow<Player?>

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

    /**
     * Plays at [factor] times normal speed, pitch corrected. `1.0` is normal.
     *
     * Only a shared session asks for this, and only by three percent, to close a gap of a second
     * or two without the stall an HLS seek costs. An engine that cannot change speed — a receiver
     * across the room — ignores it, and the session seeks instead the next time it looks.
     */
    fun setRate(factor: Float) = Unit

    /**
     * Turns the sound down to a fifth while somebody is talking over it, and back up to exactly
     * what it was before.
     *
     * Only a shared viewing asks: a friend's clip comes out of the same speaker as the episode,
     * and the phone does not duck an app against itself, so the request has to be made here. An
     * engine with no sound of its own — a receiver across the room — ignores it.
     */
    fun duck(on: Boolean) = Unit

    /** Stops playback and frees the decoder. The engine stays usable: [prepare] starts it again. */
    fun release()
}
