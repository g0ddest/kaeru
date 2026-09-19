package app.kaeru.shared

import kotlin.test.*

class PlaybackRulesTest {
    @Test fun nextEpisodeRespectsAiredBoundary() {
        assertEquals(1, PlaybackRules.nextEpisode(0, 12))
        assertEquals(6, PlaybackRules.nextEpisode(5, 12))
        assertEquals(0, PlaybackRules.nextEpisode(12, 12))
        assertEquals(0, PlaybackRules.nextEpisode(15, 12))
        assertEquals(0, PlaybackRules.nextEpisode(0, 0))
        assertEquals(1, PlaybackRules.nextEpisode(-1, 1))
        assertEquals(0, PlaybackRules.nextEpisode(Int.MAX_VALUE, Int.MAX_VALUE))
    }
    @Test fun watchedThresholdIsInclusiveAndDoesNotOverflow() {
        assertFalse(PlaybackRules.shouldMarkWatched(899, 1000))
        assertTrue(PlaybackRules.shouldMarkWatched(900, 1000))
        assertTrue(PlaybackRules.shouldMarkWatched(1001, 1000))
        assertFalse(PlaybackRules.shouldMarkWatched(0, 0))
        assertFalse(PlaybackRules.shouldMarkWatched(5, -1))
        assertFalse(PlaybackRules.shouldMarkWatched(-1, 1000))
        assertFalse(PlaybackRules.shouldMarkWatched(9, 11))
        assertTrue(PlaybackRules.shouldMarkWatched(10, 11))
        assertTrue(PlaybackRules.shouldMarkWatched(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(PlaybackRules.shouldMarkWatched(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }
    @Test fun translationKeepsRememberedOnlyWhenAvailable() {
        assertEquals(4, PlaybackRules.preferredTranslation(listOf(3, 4), 4))
        assertEquals(3, PlaybackRules.preferredTranslation(listOf(3, 4), 9))
        assertEquals(0, PlaybackRules.preferredTranslation(emptyList(), 4))
        assertEquals(-77, PlaybackRules.preferredTranslation(listOf(-77), -77))
    }
}
