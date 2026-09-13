package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.domain.model.Quality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val storeScope = TestScope(dispatcher)
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
    }

    @After
    fun tearDown() = storeScope.cancel()

    @Test
    fun `playback preferences start at the documented defaults`() = runTest(dispatcher) {
        // No stored list means the viewer has never named a studio, which is not the same as
        // naming the nine the app ships with: those are the ranker's fallback, not a setting.
        assertEquals(emptyList<String>(), prefs.preferredTranslations.first())
        assertTrue(prefs.autoplayNext.first())
        assertNull(prefs.defaultQuality.first())
        assertEquals(0.9f, prefs.watchedThreshold.first(), 0.0001f)
    }

    @Test
    fun `the preferred studio list keeps its order and survives names with spaces`() = runTest(dispatcher) {
        val wanted = listOf("Dream Cast", "SHIZA Project", "AniLibria")

        prefs.setPreferredTranslations(wanted)

        assertEquals(wanted, prefs.preferredTranslations.first())
    }

    @Test
    fun `a list emptied by the user stays empty instead of springing back to the defaults`() = runTest(dispatcher) {
        prefs.setPreferredTranslations(emptyList())

        assertEquals(emptyList<String>(), prefs.preferredTranslations.first())
    }

    @Test
    fun `autoplay and default quality round trip, and quality can be reset to best available`() =
        runTest(dispatcher) {
            prefs.setAutoplayNext(false)
            prefs.setDefaultQuality(Quality.P720)

            assertEquals(false, prefs.autoplayNext.first())
            assertEquals(Quality.P720, prefs.defaultQuality.first())

            prefs.setDefaultQuality(null)

            assertNull(prefs.defaultQuality.first())
        }

    @Test
    fun `a stored height this build does not know reads as best available`() = runTest(dispatcher) {
        store.edit { it[intPreferencesKey("default_quality")] = 2160 }

        assertNull(prefs.defaultQuality.first())
    }

    @Test
    fun `signing out drops the account keys and keeps the playback preferences`() = runTest(dispatcher) {
        prefs.setUserId(42)
        prefs.setLastFullSync(Instant.ofEpochMilli(1_000))
        prefs.setPreferredTranslations(listOf("AniDUB"))
        prefs.setAutoplayNext(false)
        prefs.setDefaultQuality(Quality.P480)

        prefs.clearAccount()

        assertNull(prefs.userId())
        assertNull(prefs.lastFullSync())
        assertEquals(listOf("AniDUB"), prefs.preferredTranslations.first())
        assertEquals(false, prefs.autoplayNext.first())
        assertEquals(Quality.P480, prefs.defaultQuality.first())
    }

    @Test
    fun `wiping the app keeps the device's own configuration and drops everything else`() = runTest(dispatcher) {
        store.edit {
            it[stringPreferencesKey("kodik_token_override")] = "typed-by-hand"
            it[stringPreferencesKey("kodik_token")] = "scraped"
            it[longPreferencesKey("kodik_token_at")] = 1_700_000_000_000
        }
        prefs.setUserId(42)
        prefs.setLastFullSync(Instant.ofEpochMilli(1_000))
        prefs.setPreferredTranslations(listOf("AniDUB"))
        prefs.setAutoplayNext(false)
        prefs.markNotificationsAsked()

        prefs.clear()

        val stored = store.data.first()
        assertTrue(prefs.notificationsAsked())
        assertEquals("typed-by-hand", stored[stringPreferencesKey("kodik_token_override")])
        assertEquals("scraped", stored[stringPreferencesKey("kodik_token")])
        assertEquals(1_700_000_000_000L, stored[longPreferencesKey("kodik_token_at")])
        assertNull(prefs.userId())
        assertNull(prefs.lastFullSync())
        assertEquals(emptyList<String>(), prefs.preferredTranslations.first())
        assertTrue(prefs.autoplayNext.first())
    }

    @Test
    fun `the notification question is remembered so it is put only once`() = runTest(dispatcher) {
        assertFalse(prefs.notificationsAsked())

        prefs.markNotificationsAsked()

        assertTrue(prefs.notificationsAsked())
    }
}
