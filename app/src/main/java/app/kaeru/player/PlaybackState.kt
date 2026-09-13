package app.kaeru.player

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality

/**
 * Everything a player screen needs to draw itself, and nothing about how it is drawn.
 *
 * [error] is the failure itself rather than a message: turning a failure into Russian copy
 * belongs to `ui.common.toUserMessage`, so the same state serves the phone, the TV and a log.
 */
data class PlaybackState(
    val target: PlaybackTarget? = null,
    val stream: EpisodeStream? = null,
    val quality: Quality? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    /** How far ahead of [positionMs] the media is already downloaded. */
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    val nextEpisodeAvailable: Boolean = false,
    val autoplayCountdownSec: Int? = null,
    val error: Throwable? = null,
    /**
     * True while the picture is on a Chromecast rather than on this device. The screen turns
     * into a remote control; everything else about playback is unchanged.
     */
    val isCasting: Boolean = false,
) {
    val episode: Int? get() = target?.episode
}

/** Things that happen once and are answered once, so they cannot live in [PlaybackState]. */
sealed interface PlaybackEvent {
    /** The last announced episode was counted as watched. Whether the show is finished is the viewer's call. */
    data class SuggestCompleted(val animeId: Int) : PlaybackEvent

    /**
     * The next episode could not be started: it has not aired, or the source would not serve it.
     * The episode that just finished is still on screen, so this is a passing message rather
     * than an error state, and [error] carries the copy.
     */
    data class NextEpisodeUnavailable(val error: Throwable) : PlaybackEvent
}

/**
 * Headers every request for a Kodik stream must carry — the manifest and each segment alike.
 * The CDN serves the links only to something that looks like the player page that got them.
 */
data class StreamHeaders(val userAgent: String, val referer: String) {
    /** Everything except the user agent, which data sources set through their own setter. */
    val requestProperties: Map<String, String> get() = mapOf("Referer" to referer)
}
