package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionRulesTest {
    private val now = 1_790_000_000_000L

    @Test
    fun theLastAnnouncedEpisodeOffersToFinishTheShow() {
        assertTrue(CompletionRules.offerCompletion(episode = 12, announcedEpisodes = 12, nextEpisodeAtMs = null, nowMs = now))
        assertTrue(CompletionRules.offerCompletion(episode = 13, announcedEpisodes = 12, nextEpisodeAtMs = null, nowMs = now))
    }

    @Test
    fun anEpisodeBeforeTheLastOffersNothing() {
        assertFalse(CompletionRules.offerCompletion(episode = 11, announcedEpisodes = 12, nextEpisodeAtMs = null, nowMs = now))
    }

    @Test
    fun anUnknownLengthNeverOffers() {
        assertFalse(CompletionRules.offerCompletion(episode = 5, announcedEpisodes = 0, nextEpisodeAtMs = null, nowMs = now))
    }

    @Test
    fun aNextEpisodeOnTheScheduleMeansThisWasNotTheEnd() {
        assertFalse(CompletionRules.offerCompletion(12, 12, nextEpisodeAtMs = now + 1, nowMs = now))
        // A date that has passed schedules nothing.
        assertTrue(CompletionRules.offerCompletion(12, 12, nextEpisodeAtMs = now, nowMs = now))
        assertTrue(CompletionRules.offerCompletion(12, 12, nextEpisodeAtMs = now - 86_400_000, nowMs = now))
    }
}
