package app.kaeru.data.kodik

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The scraped token outliving the process: the keys `AppPreferences` already knows to leave alone on a wipe. */
class DataStoreKodikTokenCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cache: DataStoreKodikTokenCache

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("prefs.preferences_pb") }
        cache = DataStoreKodikTokenCache(dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a stored token is read back by a new cache over the same store, under the keys settings expect`() = runTest {
        cache.store("0000000000000000000000000000abcd", 1_700_000_000_000)

        val entry = DataStoreKodikTokenCache(dataStore).load()!!

        assertEquals("0000000000000000000000000000abcd", entry.token)
        assertEquals(1_700_000_000_000L, entry.storedAtMillis)
        val stored = dataStore.data.first()
        assertEquals("0000000000000000000000000000abcd", stored[stringPreferencesKey("kodik_token")])
        assertEquals(1_700_000_000_000L, stored[longPreferencesKey("kodik_token_at")])
    }

    @Test
    fun `nothing stored, a blank token or a token with no timestamp is nothing`() = runTest {
        assertNull(cache.load())
        dataStore.edit { it[KodikTokenKeys.token] = "  "; it[KodikTokenKeys.storedAt] = 1L }
        assertNull(cache.load())
        dataStore.edit { it[KodikTokenKeys.token] = "abcd"; it.remove(KodikTokenKeys.storedAt) }
        assertNull(cache.load())
    }

    @Test
    fun `clearing forgets the token but not a key typed into the settings`() = runTest {
        dataStore.edit { it[KodikTokenKeys.override] = "typed-by-hand" }
        cache.store("abcd", 1L)

        cache.clear()

        assertNull(cache.load())
        assertEquals("typed-by-hand", dataStore.data.first()[KodikTokenKeys.override])
    }
}
