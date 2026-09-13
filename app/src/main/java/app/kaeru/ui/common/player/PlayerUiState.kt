package app.kaeru.ui.common.player

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
    /** The season, for the remote control's list: which episodes exist, and what is behind the viewer. */
    val episodes: List<EpisodeCell> = emptyList(),
    val autoplayCountdownSec: Int? = null,
    val errorMessage: String? = null,
    /** The picture is on a Chromecast: the screen is a remote control, not a player. */
    val isCasting: Boolean = false,
    /** What the receiver calls itself, so the remote can say where the picture went. */
    val receiverName: String? = null,
    /** The finale was counted as watched and the show is waiting to be closed. */
    val completedPrompt: Boolean = false,
    /** Something worth one line and no decision, shown and then forgotten. */
    val toast: String? = null,
) {
    /** Nothing to show yet: the first frame has not arrived and nothing has gone wrong. */
    val isLoading: Boolean get() = isBuffering && durationMs == 0L && errorMessage == null
}
