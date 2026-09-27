package app.kaeru.domain.model

import app.kaeru.shared.domain.playback.EpisodeProgressRules
import java.time.Instant

/**
 * How far into one episode this device got.
 *
 * One row per episode, which is the whole point: [WatchState] holds a single position per anime,
 * and that is why opening the sixth episode by mistake used to erase forty minutes of the seventh —
 * the new episode overwrote the only position there was. Here the two episodes are two rows, and
 * starting one never touches the other.
 */
data class EpisodeProgress(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Instant,
) {
    val fraction: Float get() = EpisodeProgressRules.fraction(positionMs, durationMs)

    /**
     * Far enough in that the viewer was watching, rather than opening the episode and leaving.
     *
     * Two ways to qualify, because one number cannot cover both shapes of episode. A minute is the
     * plain answer for a normal twenty-four minute one; the share is what makes it work for a
     * three-minute short, where a minute would be most of the run. Anything under both is a
     * mis-tap, and a mis-tap must not become the episode the app offers to continue.
     * The rule itself is [EpisodeProgressRules.started], shared with the other clients.
     */
    val started: Boolean get() = EpisodeProgressRules.started(positionMs, durationMs)

    /** Still short of the watched threshold, so there is something here to come back to. */
    fun unfinished(watchedThreshold: Float): Boolean =
        EpisodeProgressRules.unfinished(positionMs, durationMs, watchedThreshold)
}
