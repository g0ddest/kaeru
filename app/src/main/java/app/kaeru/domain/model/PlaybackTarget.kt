package app.kaeru.domain.model

/**
 * What the player was asked to play, before anything was resolved.
 *
 * [translation] is non-null only when the user picked a track by hand; otherwise
 * the choice is left to `ResolveEpisodeStream` and the remembered/preferred rules.
 */
data class PlaybackTarget(
    val animeId: Int,
    val episode: Int,
    val startPositionMs: Long,
    val translation: Translation?,
)
