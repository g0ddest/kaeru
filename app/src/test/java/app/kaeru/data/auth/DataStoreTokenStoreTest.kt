package app.kaeru.data.auth

import app.cash.turbine.test
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreTokenStoreTest {
    private val fence = SessionFence()
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("auth.preferences_pb") }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `tokens persist across wrappers and clearing emits null`() = runTest {
        val store = DataStoreTokenStore(dataStore, fence)
        store.tokens.test {
            assertNull(awaitItem())
            store.set(AuthTokens("access", "refresh", 87_400))
            assertEquals(AuthTokens("access", "refresh", 87_400), awaitItem())
            assertEquals(AuthTokens("access", "refresh", 87_400), DataStoreTokenStore(dataStore, fence).get())
            store.set(null)
            assertNull(awaitItem())
        }
        assertNull(store.get())
    }

    @Test
    fun `verified identity persists with token snapshots and is removed with credentials`() = runTest {
        val store = DataStoreTokenStore(dataStore, fence)
        store.set(AuthTokens("a", "r", 1234, 42))
        val restored = DataStoreTokenStore(dataStore, fence)
        val snapshot = restored.snapshot()
        assertEquals(AuthTokens("a", "r", 1234, 42), snapshot.tokens)
        assertTrue(restored.compareAndSet(snapshot, AuthTokens("b", "r2", 2345, 42)))
        assertEquals(42L, store.get()!!.userId)
        store.set(null)
        store.set(AuthTokens("unbound", "unbound-refresh", 3456))
        assertNull(restored.get()!!.userId)
    }

    @Test
    fun `partial credentials are anonymous and missing expiry defaults to expired`() = runTest {
        dataStore.edit { it[stringPreferencesKey("access_token")] = "access" }
        val store = DataStoreTokenStore(dataStore, fence)
        assertNull(store.get())
        dataStore.edit { it[stringPreferencesKey("refresh_token")] = "refresh" }
        assertEquals(AuthTokens("access", "refresh", 0), store.get())
    }

    @Test
    fun `conditional mutation accepts current snapshot and rejects it after rotation`() = runTest {
        val store = DataStoreTokenStore(dataStore, fence)
        store.set(AuthTokens("old", "refresh", 0))
        val snapshot = store.snapshot()
        val rotated = AuthTokens("new", "refresh-2", 87_400)
        assertTrue(store.compareAndSet(snapshot, rotated))
        assertFalse(DataStoreTokenStore(dataStore, fence).compareAndSet(snapshot, null))
        assertEquals(rotated, store.get())
    }

    @Test
    fun `logout and identical replacement credentials invalidate saved snapshot`() = runTest {
        val store = DataStoreTokenStore(dataStore, fence)
        val tokens = AuthTokens("old", "refresh", 0)
        store.set(tokens)
        val beforeLogout = store.snapshot()
        store.set(null)
        assertFalse(store.compareAndSet(beforeLogout, tokens))
        assertNull(store.get())
        store.set(tokens)
        assertFalse(store.compareAndSet(beforeLogout, null))
        val beforeNewLogin = store.snapshot()
        store.set(tokens)
        assertFalse(store.compareAndSet(beforeNewLogin, null))
        assertEquals(tokens, store.get())
    }

    @Test
    fun `concurrent conditional mutations across wrappers have exactly one winner`() = runTest {
        val store = DataStoreTokenStore(dataStore, fence)
        store.set(AuthTokens("old", "refresh", 0))
        val snapshot = store.snapshot()
        val start = CompletableDeferred<Unit>()
        val candidates = listOf(AuthTokens("one", "refresh-1", 87_400), AuthTokens("two", "refresh-2", 87_400))
        val mutations = candidates.map { tokens ->
            async(Dispatchers.Default) {
                start.await()
                DataStoreTokenStore(dataStore, fence).compareAndSet(snapshot, tokens)
            }
        }
        start.complete(Unit)
        val outcomes = mutations.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertEquals(candidates[outcomes.indexOf(true)], store.get())
    }

    @Test
    fun `cancelled token write keeps delivery closed until DataStore actor finishes persistence`() = runTest {
        assertCancelledMutationIsJoined(conditional = false)
    }

    @Test
    fun `cancelled token CAS keeps delivery closed until DataStore actor finishes persistence`() = runTest {
        assertCancelledMutationIsJoined(conditional = true)
    }

    private suspend fun TestScope.assertCancelledMutationIsJoined(conditional: Boolean) {
        val pauseWrite = AtomicBoolean(false)
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val serializer = object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                if (pauseWrite.compareAndSet(true, false)) {
                    writeEntered.complete(Unit)
                    releaseWrite.await()
                }
                PreferencesFileSerializer.writeTo(t, output)
            }
        }
        val persistent = PreferenceDataStoreFactory.create(
            storage = FileStorage(serializer) { folder.root.resolve("cancel.preferences_pb") },
            scope = scope,
        )
        val store = DataStoreTokenStore(persistent, fence)
        store.set(AuthTokens("a", "r", 1, 42))
        val snapshot = store.snapshot()
        pauseWrite.set(true)
        val clear = async {
            if (conditional) store.compareAndSet(snapshot, null) else store.set(null)
        }
        try {
            writeEntered.await()
            clear.cancel()
            runCurrent()
            var delivered = false
            fence.deliver(fence.revision.value) { delivered = true }
            assertFalse("Cancellation must not reopen delivery before the actor commits", delivered)
            assertFalse("The cancelled caller must still join its durable mutation", clear.isCompleted)
        } finally {
            releaseWrite.complete(Unit)
            clear.join()
        }
        assertTrue(clear.isCancelled)
        assertNull(store.get())
        var delivered = false
        fence.deliver(fence.revision.value) { delivered = true }
        assertTrue(delivered)
    }
}
