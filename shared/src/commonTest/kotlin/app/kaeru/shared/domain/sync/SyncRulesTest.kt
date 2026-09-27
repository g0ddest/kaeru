package app.kaeru.shared.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What of the server's is newer; the full set of cases is in `sync-vectors.json`. */
class SyncRulesTest {

    private fun local(secret: SyncSecret, announced: Map<Int, Int> = mapOf(5 to 12)) =
        LocalSyncState(emptyList(), emptyMap(), emptyMap(), mapOf(5 to secret), announced)

    @Test
    fun aTombstoneOverASecretTitleIsItWatchedThroughOnceAndNothingToSend() {
        val remote = mapOf("5" to SyncTitle(gone = 40))

        val newer = SyncRules.newer(remote, local(SyncSecret(on = true, watched = 9, at = 10)))

        assertEquals(listOf(TitleSecret(5, on = true, watched = 12, at = 10)), newer.secrets)
        assertEquals(mapOf(5 to 40L), newer.tombstones)
        assertFalse(newer.isEmpty)
        // Written as it came, the same document has nothing more for it: no second change.
        val written = newer.secrets.single().let { SyncSecret(it.on, it.watched, it.at) }
        assertTrue(SyncRules.newer(remote, local(written)).secrets.isEmpty())
        // And it is no news to the server, whose tombstone covers it.
        assertTrue(SyncMerge.without(SyncTitle(secret = written), remote.getValue("5")).isEmpty)
    }

    @Test
    fun theAnnouncedCountOfAnotherTitleIsNotThisOnes() {
        val newer = SyncRules.newer(mapOf("5" to SyncTitle(gone = 40)), local(SyncSecret(true, 9, 10), mapOf(6 to 12)))

        assertTrue(newer.secrets.isEmpty())
    }
}
