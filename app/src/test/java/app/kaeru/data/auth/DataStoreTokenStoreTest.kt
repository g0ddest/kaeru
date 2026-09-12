package app.kaeru.data.auth

import app.cash.turbine.test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreTokenStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("auth.preferences_pb") }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `tokens persist across wrappers and clearing emits null`() = runTest {
        val store = DataStoreTokenStore(dataStore)
        store.tokens.test {
            assertNull(awaitItem())
            store.set(AuthTokens("access", "refresh", 87_400))
            assertEquals(AuthTokens("access", "refresh", 87_400), awaitItem())
            assertEquals(AuthTokens("access", "refresh", 87_400), DataStoreTokenStore(dataStore).get())
            store.set(null)
            assertNull(awaitItem())
        }
        assertNull(store.get())
    }

    @Test
    fun `partial credentials are anonymous and missing expiry defaults to expired`() = runTest {
        dataStore.edit { it[stringPreferencesKey("access_token")] = "access" }
        val store = DataStoreTokenStore(dataStore)
        assertNull(store.get())
        dataStore.edit { it[stringPreferencesKey("refresh_token")] = "refresh" }
        assertEquals(AuthTokens("access", "refresh", 0), store.get())
    }

    @Test
    fun `conditional mutation accepts current snapshot and rejects it after rotation`() = runTest {
        val store = DataStoreTokenStore(dataStore)
        store.set(AuthTokens("old", "refresh", 0))
        val snapshot = store.snapshot()
        val rotated = AuthTokens("new", "refresh-2", 87_400)
        assertTrue(store.compareAndSet(snapshot, rotated))
        assertFalse(DataStoreTokenStore(dataStore).compareAndSet(snapshot, null))
        assertEquals(rotated, store.get())
    }

    @Test
    fun `logout and identical replacement credentials invalidate saved snapshot`() = runTest {
        val store = DataStoreTokenStore(dataStore)
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
        val store = DataStoreTokenStore(dataStore)
        store.set(AuthTokens("old", "refresh", 0))
        val snapshot = store.snapshot()
        val start = CompletableDeferred<Unit>()
        val candidates = listOf(AuthTokens("one", "refresh-1", 87_400), AuthTokens("two", "refresh-2", 87_400))
        val mutations = candidates.map { tokens ->
            async(Dispatchers.Default) {
                start.await()
                DataStoreTokenStore(dataStore).compareAndSet(snapshot, tokens)
            }
        }
        start.complete(Unit)
        val outcomes = mutations.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertEquals(candidates[outcomes.indexOf(true)], store.get())
    }
}
