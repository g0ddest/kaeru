package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeProgressRulesTest {

    @Test
    fun aMinuteInIsStartedEvenInAFilmWhereAMinuteIsUnderAFiftieth() {
        assertFalse(EpisodeProgressRules.started(positionMs = 59_999, durationMs = 4 * 3_600_000L))
        assertTrue(EpisodeProgressRules.started(positionMs = 60_000, durationMs = 4 * 3_600_000L))
        assertTrue(EpisodeProgressRules.started(positionMs = 60_000, durationMs = 0))
    }

    @Test
    fun aFiftiethOfAShortIsStarted() {
        // A three-minute short: 2 % of it is 3.6 seconds.
        assertFalse(EpisodeProgressRules.started(positionMs = 3_599, durationMs = 180_000))
        assertTrue(EpisodeProgressRules.started(positionMs = 3_600, durationMs = 180_000))
    }

    @Test
    fun anUnknownLengthNeedsTheWholeMinute() {
        assertFalse(EpisodeProgressRules.started(positionMs = 30_000, durationMs = 0))
        assertFalse(EpisodeProgressRules.started(positionMs = 30_000, durationMs = -5))
    }

    @Test
    fun theFractionIsClampedAndZeroForAnUnknownLength() {
        assertEquals(0f, EpisodeProgressRules.fraction(positionMs = 5_000, durationMs = 0))
        assertEquals(0.5f, EpisodeProgressRules.fraction(positionMs = 500, durationMs = 1_000))
        assertEquals(1f, EpisodeProgressRules.fraction(positionMs = 1_500, durationMs = 1_000))
        assertEquals(0f, EpisodeProgressRules.fraction(positionMs = -1, durationMs = 1_000))
    }

    @Test
    fun theWatchedThresholdIsAFractionOfTheWholeEpisode() {
        assertFalse(EpisodeProgressRules.watched(positionMs = 899_999, durationMs = 1_000_000, threshold = 0.9f))
        assertTrue(EpisodeProgressRules.watched(positionMs = 900_000, durationMs = 1_000_000, threshold = 0.9f))
        assertFalse(EpisodeProgressRules.watched(positionMs = 5_000, durationMs = 0, threshold = 0.9f))
        assertEquals(0.9f, EpisodeProgressRules.DEFAULT_WATCHED_THRESHOLD)
    }

    @Test
    fun unfinishedIsShortOfTheThresholdOnTheClampedFraction() {
        assertTrue(EpisodeProgressRules.unfinished(positionMs = 899_999, durationMs = 1_000_000, threshold = 0.9f))
        assertFalse(EpisodeProgressRules.unfinished(positionMs = 900_000, durationMs = 1_000_000, threshold = 0.9f))
        // No length: the fraction is zero, so it is unfinished — unlike `watched`, which is simply false.
        assertTrue(EpisodeProgressRules.unfinished(positionMs = 5_000, durationMs = 0, threshold = 0.9f))
    }

    @Test
    fun resumingStartsFromTheTopWhenTheEpisodeWasNotStartedOrIsFinished() {
        assertEquals(0L, EpisodeProgressRules.resumePosition(positionMs = 10_000, durationMs = 1_440_000, threshold = 0.9f))
        assertEquals(640_000L, EpisodeProgressRules.resumePosition(positionMs = 640_000, durationMs = 1_440_000, threshold = 0.9f))
        assertEquals(0L, EpisodeProgressRules.resumePosition(positionMs = 1_300_000, durationMs = 1_440_000, threshold = 0.9f))
        // A length nobody knows cannot be finished, so a started position is resumed.
        assertEquals(90_000L, EpisodeProgressRules.resumePosition(positionMs = 90_000, durationMs = 0, threshold = 0.9f))
    }
}
