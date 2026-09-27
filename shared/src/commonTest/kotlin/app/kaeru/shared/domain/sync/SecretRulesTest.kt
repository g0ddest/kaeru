package app.kaeru.shared.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Украдкой»; the full set of cases is in `sync-vectors.json`. */
class SecretRulesTest {

    @Test
    fun anOngoingShowIsNeverFinishedByItsSecretCount() {
        assertFalse(SecretRules.finished(released = false, announcedEpisodes = 12, watched = 12, nextEpisodeAtMs = null, nowMs = 0))
        assertTrue(SecretRules.finished(released = true, announcedEpisodes = 12, watched = 12, nextEpisodeAtMs = null, nowMs = 0))
        assertFalse(SecretRules.finished(released = true, announcedEpisodes = 12, watched = 11, nextEpisodeAtMs = null, nowMs = 0))
        assertFalse(SecretRules.finished(released = true, announcedEpisodes = 0, watched = 3, nextEpisodeAtMs = null, nowMs = 0))
        assertFalse(SecretRules.finished(released = true, announcedEpisodes = 12, watched = 12, nextEpisodeAtMs = 1, nowMs = 0))
    }

    @Test
    fun turningOnAndOffCountsFromShikimori() {
        assertEquals(5, SecretRules.watchedWhenTurnedOn(5))
        assertEquals(0, SecretRules.watchedWhenTurnedOn(null))
        assertEquals(9, SecretRules.episodesToSendWhenTurnedOff(9, 5))
        assertNull(SecretRules.episodesToSendWhenTurnedOff(5, 5))
    }

    @Test
    fun aMarkChangesTheCountOnlyWhenItDiffers() {
        assertEquals(4, SecretRules.watchedAfterMark(3, 4))
        assertNull(SecretRules.watchedAfterMark(4, 4))
    }
}
