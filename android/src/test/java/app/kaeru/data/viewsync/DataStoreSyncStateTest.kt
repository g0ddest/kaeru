package app.kaeru.data.viewsync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.kaeru.domain.viewsync.SyncDub
import app.kaeru.domain.viewsync.SyncPosition
import app.kaeru.domain.viewsync.SyncTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What sync keeps between launches, and that it is kept for one account only. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSyncStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val storeScope = TestScope(dispatcher)

    private fun state(): DataStoreSyncState {
        val file = File(tmp.root, "prefs.preferences_pb")
        return DataStoreSyncState(PreferenceDataStoreFactory.create(scope = storeScope) { file })
    }

    @After
    fun tearDown() = storeScope.cancel()

    @Test
    fun `the outbox comes back whole for its account and empty for any other`() = runTest(dispatcher) {
        val store = state()
        val titles = mapOf(
            "5" to SyncTitle(dub = SyncDub(610, "AniLibria.TV", 3), eps = mapOf("1" to SyncPosition(1, 2, 3))),
            "6" to SyncTitle(gone = 9),
        )
        store.setOutbox(42, titles)

        assertEquals(titles, store.outbox(42))
        assertTrue(store.outbox(77).isEmpty())

        store.setOutbox(42, emptyMap())
        assertTrue(store.outbox(42).isEmpty())
    }

    @Test
    fun `seeded accounts and dub stamps are remembered`() = runTest(dispatcher) {
        val store = state()
        store.markSeeded(42)
        store.markSeeded(77)
        store.forgetSeeded(77)
        store.setDubStamps(42, mapOf(5 to 100L, 6 to 200L))

        assertEquals(setOf(42L), store.seeded())
        assertEquals(mapOf(5 to 100L, 6 to 200L), store.dubStamps(42))
        assertTrue(store.dubStamps(77).isEmpty())
    }
}
