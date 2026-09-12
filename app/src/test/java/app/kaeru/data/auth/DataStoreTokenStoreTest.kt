package app.kaeru.data.auth

import app.cash.turbine.test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
