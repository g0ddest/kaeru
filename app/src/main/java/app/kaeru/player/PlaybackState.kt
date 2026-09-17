package app.kaeru.player

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation

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
    /**
     * The engine has read this episode's manifest, so a seek lands where it is asked to.
     *
     * False from the moment a transition that will prepare again begins — a fresh episode, another
     * voice, another rung, another engine, a retry — until the engine reports a length for what
     * it was handed. [durationMs] cannot stand in for it: a change of voice keeps the length it
     * already knows so the timeline does not flash empty.
     */
    val ready: Boolean = false,
    val positionMs: Long = 0,
    /** How far ahead of [positionMs] the media is already downloaded. */
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    /** The episode is close enough to its end that what comes after it is worth saying. */
    val nextEpisodeDue: Boolean = false,
    /**
     * How many episodes of this anime have actually aired, read once when the episode opened.
     * Zero while nothing is playing, and for an anime this device has never cached.
     */
    val airedEpisodes: Int = 0,
    val autoplayCountdownSec: Int? = null,
    val error: Throwable? = null,
    /**
     * The voice this episode was asked for in, when the one in [stream] is not it: that voice did
     * not carry the episode and another stood in. Null whenever what plays is what was asked for.
     *
     * Kept here rather than derived, because two things have to read it back: the position ticks,
     * which must not write the stand-in over the voice the viewer chose, and the next episode,
     * which is asked for in the chosen voice again rather than in whatever stood in last time.
     */
    val insteadOf: Translation? = null,
    /**
     * [error] came from reading the copy on this device rather than from the source.
     *
     * The one thing the screen cannot work out for itself. «Не удалось воспроизвести скачанную
     * серию» is only true when the file is what failed, and every proxy for that is wrong
     * somewhere: an episode can be downloaded and still be streaming — another voice, a
     * Chromecast — and a failure there is the source's, with the download sitting there perfectly
     * playable. Set exactly where the difference is known: false for anything that comes out of a
     * resolve, true for a decoder failure while the download was the thing being read.
     */
    val failedReadingDownload: Boolean = false,
    /**
     * True while the picture is on a Chromecast rather than on this device. The screen turns
     * into a remote control; everything else about playback is unchanged.
     */
    val isCasting: Boolean = false,
) {
    val episode: Int? get() = target?.episode

    /**
     * Whether an episode after this one exists to play.
     *
     * Aired episodes, not announced ones: a season of twenty-four with seven broadcast has
     * nothing after the seventh, and offering it ends at «Серия ещё не появилась в Kodik».
     */
    val hasNextEpisode: Boolean get() = airedEpisodes > 0 && (target?.episode ?: 0) < airedEpisodes
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

    /**
     * The voice asked for did not carry [episode], and [playing] stood in for it. Said once per
     * substitution, not once per resolve: a link re-signed behind the viewer's back lands on the
     * same stand-in and is not news.
     */
    data class TranslationSubstituted(val askedFor: Translation, val playing: Translation, val episode: Int) :
        PlaybackEvent
}

/**
 * Headers every request for a Kodik stream must carry — the manifest and each segment alike.
 * The CDN serves the links only to something that looks like the player page that got them.
 */
data class StreamHeaders(val userAgent: String, val referer: String) {
    /** Everything except the user agent, which data sources set through their own setter. */
    val requestProperties: Map<String, String> get() = mapOf("Referer" to referer)
}
