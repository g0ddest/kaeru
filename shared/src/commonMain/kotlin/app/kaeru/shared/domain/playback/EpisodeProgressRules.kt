package app.kaeru.shared.domain.playback

/**
 * How far into one episode counts as started, as finished, and where to pick it up again.
 *
 * Positions and lengths in milliseconds; a length of zero or less is «not known yet».
 */
object EpisodeProgressRules {

    /** A minute in is watching, whatever the episode's length. */
    const val STARTED_MS: Long = 60_000L

    /** Or a fiftieth of it, for an episode too short for a minute to mean anything. */
    const val STARTED_FRACTION: Float = 0.02f

    /** The share of an episode that counts it as watched, until the viewer picks another. */
    const val DEFAULT_WATCHED_THRESHOLD: Float = 0.9f

    /** How much of the episode is behind the viewer, 0…1; zero while the length is unknown. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Far enough in that the viewer was watching, rather than opening the episode and leaving.
     *
     * A minute is the plain answer for a normal episode; the share is what makes it work for a
     * three-minute short. Anything under both is a mis-tap, never an episode to offer to continue.
     */
    fun started(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= STARTED_MS || (durationMs > 0 && positionMs.toFloat() / durationMs >= STARTED_FRACTION)

    /** Whether enough of the episode is behind the viewer to call it watched. Never, while the length is unknown. */
    fun watched(positionMs: Long, durationMs: Long, threshold: Float): Boolean =
        durationMs > 0 && positionMs.toFloat() / durationMs >= threshold

    /** Still short of the threshold on the clamped [fraction], so there is something here to come back to. */
    fun unfinished(positionMs: Long, durationMs: Long, threshold: Float): Boolean =
        fraction(positionMs, durationMs) < threshold

    /** Where opening this episode again starts: from the top unless it was started and is not yet watched. */
    fun resumePosition(positionMs: Long, durationMs: Long, threshold: Float): Long = when {
        !started(positionMs, durationMs) -> 0L
        watched(positionMs, durationMs, threshold) -> 0L
        else -> positionMs
    }
}
