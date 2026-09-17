package app.kaeru.ui.common.player

import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.EpisodeCell
import java.time.Instant

/** Which chooser is open over the video, if any. */
enum class PlayerSheet { TRANSLATIONS, QUALITY }

/**
 * The player screen, phone and TV alike, as plain values: no player, no media items, and
 * failures already in the viewer's language.
 */
data class PlayerUiState(
    val title: String = "",
    val posterUrl: String? = null,
    val episode: Int = 0,
    /** How many episodes the show has to offer, for a screen that lists them. */
    val availableEpisodes: Int = 0,
    val translationTitle: String? = null,
    val translationId: Int? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMs: Long = 0,
    /** How far ahead of [positionMs] the media is downloaded, for the pale head on the timeline. */
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    val quality: Quality? = null,
    val qualities: List<Quality> = emptyList(),
    /** Whether a quality is settled on for every episode rather than picked one episode at a time. */
    val rememberQuality: Boolean = false,
    /** The tracks on offer, ranked, each saying whether this viewer keeps choosing it. */
    val translations: List<RankedTranslation> = emptyList(),
    val loadingTranslations: Boolean = false,
    val sheet: PlayerSheet? = null,
    /** An episode after this one has aired, so «Следующая серия» leads somewhere. */
    val nextEpisodeAvailable: Boolean = false,
    /** The episode is in its last half-minute: time to say what comes after it, or that nothing does. */
    val episodeEnding: Boolean = false,
    /** When the episode after the last aired one is due, for a screen that has to wait for it. */
    val nextEpisodeAt: Instant? = null,
    /**
     * The show has episodes still to be broadcast. False for a finished one, where running out of
     * episodes is the end of the story rather than a wait.
     */
    val moreEpisodesComing: Boolean = false,
    /** The season, for the remote control's list: which episodes exist, and what is behind the viewer. */
    val episodes: List<EpisodeCell> = emptyList(),
    val autoplayCountdownSec: Int? = null,
    val errorMessage: String? = null,
    /**
     * Why the episode could not be had, when that is what [errorMessage] is about; null for every
     * other failure. The one that matters to the screen is «ни в одной озвучке»: the way out of
     * that is the season list, and offering the dub picker over it would be offering nothing.
     */
    val episodeUnavailable: EpisodeUnavailableReason? = null,
    /** The picture is on a Chromecast: the screen is a remote control, not a player. */
    val isCasting: Boolean = false,
    /** What the receiver calls itself, so the remote can say where the picture went. */
    val receiverName: String? = null,
    /** The finale was counted as watched and the show is waiting to be closed. */
    val completedPrompt: Boolean = false,
    /**
     * There is no network. What is on the device still plays; everything that needs Kodik —
     * another voice, another episode, a rung that was not downloaded — does not.
     */
    val offline: Boolean = false,
    /**
     * This episode's download, in whatever state it is in, or null when there is none. The
     * screen draws one control from it: «скачать», the progress of a download under way, or
     * «удалить» for an episode already on the device.
     */
    val download: EpisodeDownload? = null,
    /**
     * What failed was the copy on this device, not the source.
     *
     * Carried from the controller because no arrangement of the fields here can stand in for it: an
     * episode can be downloaded and still be streaming — another voice, a Chromecast — and a
     * failure there belongs to Kodik with the download sitting beside it, perfectly playable.
     */
    val failedReadingDownload: Boolean = false,
    /** Something worth one line and no decision, shown and then forgotten. */
    val toast: String? = null,
) {
    /** Nothing to show yet: the first frame has not arrived and nothing has gone wrong. */
    val isLoading: Boolean get() = isBuffering && durationMs == 0L && errorMessage == null
}
