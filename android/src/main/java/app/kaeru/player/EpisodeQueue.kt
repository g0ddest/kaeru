package app.kaeru.player

import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality

/**
 * The player-side arithmetic of an episode: where a seek lands, which rung to start on, what the
 * next target is.
 *
 * When the next-episode card appears, when the countdown runs and when an episode counts as
 * watched are rules every client shares, and live in `shared`
 * ([app.kaeru.shared.domain.playback.NextEpisodeRules], [app.kaeru.shared.domain.playback.EpisodeProgressRules]).
 */
object EpisodeQueue {
    /** One step of the seek buttons and of a double tap. */
    const val SEEK_STEP_MS = 10_000L

    /** One opening, give or take — the "+85 с" button. */
    const val SKIP_INTRO_MS = 85_000L

    /** How often the position is written down while playing. */
    const val PROGRESS_INTERVAL_MS = 5_000L

    /** The same anime and the same track, one episode on, from the top. */
    fun next(current: PlaybackTarget): PlaybackTarget =
        PlaybackTarget(current.animeId, current.episode + 1, startPositionMs = 0, translation = current.translation)

    /**
     * A seek that stays inside the episode. A duration that is not known yet only clamps the
     * bottom: the player will refuse an overshoot by itself once it knows better.
     */
    fun clampSeek(positionMs: Long, durationMs: Long): Long = when {
        positionMs < 0 -> 0
        durationMs > 0 && positionMs > durationMs -> durationMs
        else -> positionMs
    }

    /**
     * Which rung to start on: the one the viewer settled on, and otherwise the best the
     * source offers. A remembered rung this stream does not carry is not an error — Kodik
     * lists different heights per episode — it just does not apply here.
     */
    fun startQuality(offered: Set<Quality>, preferred: Quality?): Quality? =
        preferred?.takeIf { it in offered } ?: offered.maxByOrNull { it.height }
}
