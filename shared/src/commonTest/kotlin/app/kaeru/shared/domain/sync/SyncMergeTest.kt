package app.kaeru.shared.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergeTest {

    @Test
    fun mergeAndWithoutTreatSecretLikeAnyOtherField() {
        val older = SyncTitle(secret = SyncSecret(true, 2, 10))
        val newer = SyncTitle(secret = SyncSecret(false, 5, 20))
        assertEquals(newer.secret, SyncMerge.merge(older, newer).secret)
        assertEquals(newer.secret, SyncMerge.merge(newer, older).secret)
        assertNull(SyncMerge.without(older, newer).secret)
        assertEquals(newer.secret, SyncMerge.without(newer, older).secret)
        assertNull(SyncMerge.without(newer, SyncTitle(gone = 20)).secret)
    }

    @Test
    fun aTitleWithAnEmptyEpisodeMapIsEmpty() {
        assertTrue(SyncTitle(eps = emptyMap()).isEmpty)
        assertTrue(!SyncTitle(gone = 0).isEmpty)
    }
}
