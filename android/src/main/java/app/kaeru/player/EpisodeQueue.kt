package app.kaeru.player

import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality

/**
 * The arithmetic of "where are we in this episode, and what comes after it".
 *
 * Pulled out of the controller so the rules that decide when the next-episode card
 * appears, when an episode counts as watched and where a seek lands can be read and
 * tested without a player attached.
 */
object EpisodeQueue {
    /** How long before the end the next episode is offered. */
    const val NEXT_EPISODE_LEAD_MS = 30_000L

    /** How long the viewer has to say no before the next episode starts by itself. */
    const val AUTOPLAY_COUNTDOWN_SEC = 10

    /** Countdown territory: the last seconds, where the offer turns into an intention. */
    private const val COUNTDOWN_LEAD_MS = AUTOPLAY_COUNTDOWN_SEC * 1_000L

    /** One step of the seek buttons and of a double tap. */
    const val SEEK_STEP_MS = 10_000L

    /** One opening, give or take — the "+85 с" button. */
    const val SKIP_INTRO_MS = 85_000L

    /** How often the position is written down while playing. */
    const val PROGRESS_INTERVAL_MS = 5_000L

    /** Whether the episode is close enough to its end to offer the next one. */
    fun nextEpisodeDue(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
        ended || remaining(positionMs, durationMs)?.let { it <= NEXT_EPISODE_LEAD_MS } == true

    /** Whether the offer should become a countdown. */
    fun countdownDue(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
        ended || remaining(positionMs, durationMs)?.let { it <= COUNTDOWN_LEAD_MS } == true

    /** Whether enough of the episode is behind the viewer to call it watched. */
    fun watched(positionMs: Long, durationMs: Long, threshold: Float): Boolean =
        durationMs > 0 && positionMs.toFloat() / durationMs >= threshold

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

    private fun remaining(positionMs: Long, durationMs: Long): Long? =
        if (durationMs <= 0) null else (durationMs - positionMs).coerceAtLeast(0)
}
