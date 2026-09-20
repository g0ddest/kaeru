package app.kaeru.domain.together

/** What to do about a gap between where this side is and where the friend's side is. */
sealed interface SyncAction {
    /** Close enough. The most common answer by a very long way. */
    data object None : SyncAction

    /**
     * Play slightly slow or slightly fast until the gap closes. `1.0` means the correction is over
     * and normal speed goes back on.
     */
    data class Rate(val factor: Float) : SyncAction

    /** Jump. Small enough that a viewer reads it as the video stuttering. */
    data class SeekTo(val positionMs: Long) : SyncAction

    /** Jump, and say so — at this size the picture changes and an unexplained jump is alarming. */
    data class SeekAndNotify(val positionMs: Long) : SyncAction
}

/**
 * The one rule that keeps two phones on the same second, and it is mostly the rule to do nothing.
 *
 * Numbers sit between Jellyfin's SyncPlay and Syncplay's, pushed apart because Kodik is HLS: a
 * seek lands on a segment boundary and costs a visible stall, so the band where speed is nudged
 * instead of seeking is wide. Below half a second nothing happens at all, because two HLS players
 * never agree to the millisecond and chasing that would mean correcting forever.
 *
 * Bands, each one starting where the last stops: under 500 ms nothing; up to 2 s speed; up to and
 * including 10 s a quiet seek; past 10 s a seek the viewer is told about.
 *
 * Drift is only a number when both sides are playing. A friend who is paused is not behind, they
 * are paused, and the way that reaches this side is a [TogetherMessage.Pause] — never a [State].
 * Reading a pause out of drift is how one person stalling for four seconds ends up pausing the
 * other one for good.
 */
object SyncPolicy {
    /** Under this, do nothing. Two HLS players will not agree more closely than this anyway. */
    const val IGNORE_MS = 500L

    /** From here a nudge in speed cannot catch up in reasonable time, so seek. */
    const val SEEK_MS = 2_000L

    /** Past here the jump is big enough to need a sentence next to it. */
    const val NOTIFY_MS = 10_000L

    /** Where a correction in progress stops: tighter than [IGNORE_MS] so it does not re-arm at once. */
    const val CONVERGED_MS = 200L

    /** Three percent. Inaudible with media3's pitch correction, and a second recovered per 33. */
    const val SLOW = 0.97f
    const val FAST = 1.03f
    const val NORMAL = 1.0f

    /**
     * @param offsetMs how far the friend's clock is from this one, so `remoteMs + offsetMs` is
     *   where they actually are on this device's clock.
     * @param correcting whether a [SyncAction.Rate] other than [NORMAL] is in force right now. It
     *   is what makes the correction stop at [CONVERGED_MS] instead of at [IGNORE_MS], and what
     *   gets normal speed back when the reason for it goes away.
     */
    fun decide(
        localMs: Long,
        remoteMs: Long,
        remotePlaying: Boolean,
        localPlaying: Boolean,
        offsetMs: Long,
        correcting: Boolean = false,
    ): SyncAction {
        val settled = if (correcting) SyncAction.Rate(NORMAL) else SyncAction.None
        if (!remotePlaying || !localPlaying) return settled
        val target = remoteMs + offsetMs
        val drift = localMs - target
        val gap = kotlin.math.abs(drift)
        val nudge = SyncAction.Rate(if (drift > 0) SLOW else FAST)
        return when {
            gap < CONVERGED_MS -> settled
            gap < IGNORE_MS -> if (correcting) nudge else SyncAction.None
            gap < SEEK_MS -> nudge
            gap <= NOTIFY_MS -> SyncAction.SeekTo(target)
            else -> SyncAction.SeekAndNotify(target)
        }
    }
}
