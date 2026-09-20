package app.kaeru.domain.playback

/**
 * One stretch of an episode worth stepping over, in milliseconds from its start.
 *
 * Milliseconds rather than the seconds the source speaks in, because everything that will ever
 * compare it — a position, a seek, a length — is in milliseconds, and one conversion at the edge
 * is cheaper than a rounding argument in the middle.
 */
data class SkipInterval(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/** What is known about one episode's opening and ending. Either half can be missing, and usually is. */
data class SkipMarks(val opening: SkipInterval? = null, val ending: SkipInterval? = null) {
    val isEmpty: Boolean get() = opening == null && ending == null

    companion object {
        /** Nothing is known about this episode; no button is drawn and nothing skips itself. */
        val NONE = SkipMarks()
    }
}

/** Which of the two the player is offering to step over. */
enum class SkipKind { OPENING, ENDING }

/** The offer on screen right now: what it steps over, and where it would land. */
data class SkipOffer(val kind: SkipKind, val interval: SkipInterval)

/**
 * Common sense applied to community data, and the ten seconds an offer is worth.
 *
 * The marks come from people, and people mark things wrongly: the spike found «endings» at the
 * fifth and at the hundred-and-seventeenth second of an episode. Nothing downstream can tell
 * a wrong interval from a right one once it is drawn as a button, so every interval passes
 * through here first and anything implausible is dropped in silence — the button simply never
 * appears, which is exactly what a viewer with no marks at all sees.
 *
 * Arithmetic only: no clock and no timer. The ten seconds a button hangs for are ten seconds of
 * played video, which is what makes a paused player hold its offer and a test able to prove all
 * of this by handing the rules a sequence of positions.
 */
object SkipRules {

    /** How long an offer stands after playback walks into the interval it is about. */
    const val BUTTON_WINDOW_MS = 10_000L

    /** An opening is expected in the first five minutes; anything later is somebody's mistake. */
    const val OPENING_STARTS_WITHIN_MS = 5 * 60_000L

    /** An ending is expected to reach into the last three minutes, and usually to the very end. */
    const val ENDING_ENDS_WITHIN_MS = 3 * 60_000L

    /** Shorter than this is a jingle, longer is a chunk of the episode. */
    const val MIN_LENGTH_MS = 60_000L
    const val MAX_LENGTH_MS = 150_000L

    /** The opening, or null when what was offered cannot be one of this episode. */
    fun opening(interval: SkipInterval?, durationMs: Long): SkipInterval? =
        interval?.takeIf { plausible(it, durationMs) && it.startMs <= OPENING_STARTS_WITHIN_MS }

    /** The ending, or null when what was offered cannot be one of this episode. */
    fun ending(interval: SkipInterval?, durationMs: Long): SkipInterval? =
        interval?.takeIf { plausible(it, durationMs) && it.endMs >= durationMs - ENDING_ENDS_WITHIN_MS }

    /** Both halves put through the same sieve; what does not survive it is simply not there. */
    fun accept(marks: SkipMarks, durationMs: Long): SkipMarks =
        SkipMarks(opening(marks.opening, durationMs), ending(marks.ending, durationMs))

    /**
     * What to offer at [positionMs], or null for the rest of the episode.
     *
     * The marks are sieved here too rather than trusted, so this answers the same way whether it
     * is given what a cache holds or what a source just said.
     */
    fun offer(marks: SkipMarks, positionMs: Long, durationMs: Long): SkipOffer? {
        val accepted = accept(marks, durationMs)
        accepted.opening?.let { if (inWindow(it, positionMs)) return SkipOffer(SkipKind.OPENING, it) }
        accepted.ending?.let { if (inWindow(it, positionMs)) return SkipOffer(SkipKind.ENDING, it) }
        return null
    }

    /**
     * Whether the ending should now step aside by itself: its ten seconds are behind the viewer
     * and the ending is still playing.
     *
     * Bounded at both ends on purpose. Past the interval the episode is over on its own terms,
     * and the countdown that already exists is the thing that belongs there.
     */
    fun endingSkipDue(marks: SkipMarks, positionMs: Long, durationMs: Long): Boolean {
        val ending = ending(marks.ending, durationMs) ?: return false
        return positionMs >= ending.startMs + BUTTON_WINDOW_MS && positionMs < ending.endMs
    }

    /**
     * Whether [positionMs] is anywhere inside the ending.
     *
     * What tells playing into the ending from jumping into it: the position before this one was
     * already in the ending when the episode walked there, and was somewhere else entirely when
     * the viewer dragged the bar.
     */
    fun insideEnding(marks: SkipMarks, positionMs: Long, durationMs: Long): Boolean {
        val ending = ending(marks.ending, durationMs) ?: return false
        return positionMs >= ending.startMs && positionMs < ending.endMs
    }

    /** How much playing it takes after a seek before a position is the episode's own again. */
    const val SEEK_SETTLE_MS = 1_000L

    private fun inWindow(interval: SkipInterval, positionMs: Long): Boolean =
        positionMs >= interval.startMs && positionMs < interval.startMs + BUTTON_WINDOW_MS

    /**
     * Whether an interval could belong to a file of this length at all.
     *
     * The length check is the whole of «сопоставление по длине» on this side: AniSkip is asked
     * with the length the engine reports and answers with what was marked for it, and an interval
     * that runs past the end of what is playing was marked for a different file.
     */
    private fun plausible(interval: SkipInterval, durationMs: Long): Boolean =
        durationMs > 0 && interval.startMs >= 0 && interval.endMs <= durationMs &&
            interval.lengthMs in MIN_LENGTH_MS..MAX_LENGTH_MS
}
