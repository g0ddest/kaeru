package app.kaeru.ui.common.player

import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation

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
    val durationMs: Long = 0,
    val quality: Quality? = null,
    val qualities: List<Quality> = emptyList(),
    val translations: List<Translation> = emptyList(),
    val loadingTranslations: Boolean = false,
    val sheet: PlayerSheet? = null,
    val nextEpisodeAvailable: Boolean = false,
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
