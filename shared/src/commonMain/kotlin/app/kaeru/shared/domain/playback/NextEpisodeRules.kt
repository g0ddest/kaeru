package app.kaeru.shared.domain.playback

import kotlin.math.ceil

/** Where a show is in its run, as the catalogue says. */
enum class AiringStatus { ONGOING, RELEASED, ANONS }

/**
 * What comes after this episode, and when to say so.
 *
 * Arithmetic over positions in milliseconds, with no clock: the offer and the countdown are about
 * played video, so a paused player holds them.
 */
object NextEpisodeRules {

    /** How long before the end the next episode is offered. */
    const val NEXT_EPISODE_LEAD_MS: Long = 30_000L

    /** How long the viewer has to say no before the next episode starts by itself. */
    const val AUTOPLAY_COUNTDOWN_SEC: Int = 10

    private const val COUNTDOWN_LEAD_MS: Long = AUTOPLAY_COUNTDOWN_SEC * 1_000L

    /**
     * Episodes actually there to watch: aired so far for an ongoing show, nothing for an
     * announcement that has not started, and the announced total for a finished show — falling
     * back to what aired if the total itself is unknown.
     */
    fun availableEpisodes(status: AiringStatus, episodes: Int, episodesAired: Int): Int = when (status) {
        AiringStatus.ONGOING -> episodesAired
        AiringStatus.ANONS -> 0
        AiringStatus.RELEASED -> if (episodes > 0) episodes else episodesAired
    }

    /**
     * Whether an episode after [episode] exists to play. Available episodes, not announced ones:
     * a season of twenty-four with seven broadcast has nothing after the seventh. An unknown
     * count (zero) has no next episode.
     */
    fun hasNextEpisode(episode: Int, availableEpisodes: Int): Boolean =
        availableEpisodes > 0 && episode < availableEpisodes

    /** Whether the episode is close enough to its end — the last half minute — to offer the next one. */
    fun nextEpisodeDue(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
        ended || remaining(positionMs, durationMs)?.let { it <= NEXT_EPISODE_LEAD_MS } == true

    /** Whether the offer should become a countdown: the last ten seconds. */
    fun countdownDue(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
        ended || remaining(positionMs, durationMs)?.let { it <= COUNTDOWN_LEAD_MS } == true

    /**
     * Seconds left on the autoplay countdown, rounded up, or null while it is not due.
     * Zero once the episode has ended. Whether autoplay is on, and whether there is a next
     * episode at all, is the caller's to ask first.
     */
    fun countdownSeconds(positionMs: Long, durationMs: Long, ended: Boolean): Int? {
        if (!countdownDue(positionMs, durationMs, ended)) return null
        if (ended || durationMs <= 0) return 0
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        return ceil(remaining / 1000.0).toInt().coerceIn(0, AUTOPLAY_COUNTDOWN_SEC)
    }

    private fun remaining(positionMs: Long, durationMs: Long): Long? =
        if (durationMs <= 0) null else (durationMs - positionMs).coerceAtLeast(0)
}
