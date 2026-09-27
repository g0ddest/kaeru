package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextEpisodeRulesTest {

    // --- how many episodes there are to play -----------------------------------------------------

    @Test
    fun anOngoingShowHasOnlyWhatHasAiredAvailableWhateverTheSeasonPromises() {
        assertEquals(7, NextEpisodeRules.availableEpisodes(AiringStatus.ONGOING, episodes = 24, episodesAired = 7))
        assertEquals(0, NextEpisodeRules.availableEpisodes(AiringStatus.ONGOING, episodes = 24, episodesAired = 0))
    }

    @Test
    fun anAnnouncementHasNothingAvailableWhateverLengthItPromises() {
        assertEquals(0, NextEpisodeRules.availableEpisodes(AiringStatus.ANONS, episodes = 12, episodesAired = 0))
        assertEquals(0, NextEpisodeRules.availableEpisodes(AiringStatus.ANONS, episodes = 0, episodesAired = 0))
        assertEquals(0, NextEpisodeRules.availableEpisodes(AiringStatus.ANONS, episodes = 12, episodesAired = 5))
    }

    @Test
    fun aReleasedShowCountsItsWholeAnnouncedRunOrWhatAiredWhenTheTotalIsUnknown() {
        assertEquals(12, NextEpisodeRules.availableEpisodes(AiringStatus.RELEASED, episodes = 12, episodesAired = 0))
        assertEquals(24, NextEpisodeRules.availableEpisodes(AiringStatus.RELEASED, episodes = 0, episodesAired = 24))
    }

    @Test
    fun thereIsANextEpisodeOnlyBelowAKnownCount() {
        assertTrue(NextEpisodeRules.hasNextEpisode(episode = 6, availableEpisodes = 7))
        assertFalse(NextEpisodeRules.hasNextEpisode(episode = 7, availableEpisodes = 7))
        assertFalse(NextEpisodeRules.hasNextEpisode(episode = 9, availableEpisodes = 7))
        assertFalse(NextEpisodeRules.hasNextEpisode(episode = 1, availableEpisodes = 0))
    }

    // --- the end of an episode -------------------------------------------------------------------

    @Test
    fun theNextEpisodeCardAppearsHalfAMinuteBeforeTheEnd() {
        val duration = 1_440_000L
        assertFalse(NextEpisodeRules.nextEpisodeDue(duration - 30_001, duration, ended = false))
        assertTrue(NextEpisodeRules.nextEpisodeDue(duration - 30_000, duration, ended = false))
        assertEquals(30_000L, NextEpisodeRules.NEXT_EPISODE_LEAD_MS)
    }

    @Test
    fun anEpisodeWhoseDurationIsNotKnownYetNeverLooksNearlyOver() {
        assertFalse(NextEpisodeRules.nextEpisodeDue(positionMs = 0, durationMs = 0, ended = false))
        assertFalse(NextEpisodeRules.countdownDue(positionMs = 0, durationMs = 0, ended = false))
        assertNull(NextEpisodeRules.countdownSeconds(positionMs = 0, durationMs = 0, ended = false))
    }

    @Test
    fun anEpisodeThatEndedIsOverRegardlessOfWhatTheClockSays() {
        assertTrue(NextEpisodeRules.nextEpisodeDue(positionMs = 0, durationMs = 0, ended = true))
        assertTrue(NextEpisodeRules.countdownDue(positionMs = 0, durationMs = 0, ended = true))
        assertEquals(0, NextEpisodeRules.countdownSeconds(positionMs = 0, durationMs = 0, ended = true))
        assertEquals(0, NextEpisodeRules.countdownSeconds(positionMs = 5_000, durationMs = 1_440_000, ended = true))
    }

    @Test
    fun theCountdownStartsOnlyInTheLastTenSeconds() {
        val duration = 1_440_000L
        assertFalse(NextEpisodeRules.countdownDue(duration - 10_001, duration, ended = false))
        assertTrue(NextEpisodeRules.countdownDue(duration - 10_000, duration, ended = false))
        assertEquals(10, NextEpisodeRules.AUTOPLAY_COUNTDOWN_SEC)
    }

    @Test
    fun theCountdownIsTheRemainingSecondsRoundedUp() {
        val duration = 1_440_000L
        assertNull(NextEpisodeRules.countdownSeconds(duration - 10_001, duration, ended = false))
        assertEquals(10, NextEpisodeRules.countdownSeconds(duration - 10_000, duration, ended = false))
        assertEquals(10, NextEpisodeRules.countdownSeconds(duration - 9_001, duration, ended = false))
        assertEquals(9, NextEpisodeRules.countdownSeconds(duration - 9_000, duration, ended = false))
        assertEquals(1, NextEpisodeRules.countdownSeconds(duration - 1, duration, ended = false))
        assertEquals(0, NextEpisodeRules.countdownSeconds(duration, duration, ended = false))
        assertEquals(0, NextEpisodeRules.countdownSeconds(duration + 5_000, duration, ended = false))
    }
}
