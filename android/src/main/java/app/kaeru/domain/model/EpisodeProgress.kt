package app.kaeru.domain.model

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
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Far enough in that the viewer was watching, rather than opening the episode and leaving.
     *
     * Two ways to qualify, because one number cannot cover both shapes of episode. A minute is the
     * plain answer for a normal twenty-four minute one; the share is what makes it work for a
     * three-minute short, where a minute would be most of the run. Anything under both is a
     * mis-tap, and a mis-tap must not become the episode the app offers to continue.
     */
    val started: Boolean
        get() = positionMs >= STARTED_MS || (durationMs > 0 && positionMs.toFloat() / durationMs >= STARTED_FRACTION)

    /** Still short of the watched threshold, so there is something here to come back to. */
    fun unfinished(watchedThreshold: Float): Boolean = fraction < watchedThreshold

    companion object {
        /** A minute in is watching, whatever the episode's length. */
        const val STARTED_MS = 60_000L

        /** Or a fiftieth of it, for an episode too short for a minute to mean anything. */
        const val STARTED_FRACTION = 0.02f
    }
}
