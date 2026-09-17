package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadedEpisode
import app.kaeru.domain.model.Account
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
        assertTrue(prefs.pipOnLeave.first())
    }

    @Test
    fun `the floating window switch is on until somebody turns it off, and stays off`() = runTest(dispatcher) {
        prefs.setPipOnLeave(false)
        assertEquals(false, prefs.pipOnLeave.first())

        prefs.setPipOnLeave(true)
        assertEquals(true, prefs.pipOnLeave.first())
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
        prefs.setPipOnLeave(false)

        prefs.clearAccount()

        assertNull(prefs.userId())
        assertNull(prefs.lastFullSync())
        assertEquals(listOf("AniDUB"), prefs.preferredTranslations.first())
        assertEquals(false, prefs.autoplayNext.first())
        assertEquals(Quality.P480, prefs.defaultQuality.first())
        assertEquals(false, prefs.pipOnLeave.first())
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
        prefs.setPipOnLeave(false)
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
        assertTrue(prefs.pipOnLeave.first())
    }

    @Test
    fun `the notification question is remembered so it is put only once`() = runTest(dispatcher) {
        assertFalse(prefs.notificationsAsked())

        prefs.markNotificationsAsked()

        assertTrue(prefs.notificationsAsked())
    }

    @Test
    fun `the watched threshold round trips`() = runTest(dispatcher) {
        prefs.setWatchedThreshold(0.85f)

        assertEquals(0.85f, prefs.watchedThreshold.first(), 0.0001f)
    }

    @Test
    fun `a threshold outside the range a share of an episode can have is pulled back into it`() =
        runTest(dispatcher) {
            prefs.setWatchedThreshold(3f)
            assertEquals(1f, prefs.watchedThreshold.first(), 0.0001f)

            prefs.setWatchedThreshold(-1f)
            assertEquals(0.5f, prefs.watchedThreshold.first(), 0.0001f)
        }

    @Test
    fun `a threshold that is not a number is not written`() = runTest(dispatcher) {
        prefs.setWatchedThreshold(0.8f)

        prefs.setWatchedThreshold(Float.NaN)

        assertEquals(0.8f, prefs.watchedThreshold.first(), 0.0001f)
    }

    @Test
    fun `the kodik token round trips and an empty one clears the key`() = runTest(dispatcher) {
        assertNull(prefs.kodikToken.first())

        prefs.setKodikToken("  typed-by-hand  ")
        assertEquals("typed-by-hand", prefs.kodikToken.first())

        prefs.setKodikToken("   ")
        assertNull(prefs.kodikToken.first())
        assertNull(store.data.first()[stringPreferencesKey("kodik_token_override")])
    }

    @Test
    fun `the kodik token belongs to the device and survives a sign-out`() = runTest(dispatcher) {
        prefs.setUserId(42)
        prefs.setKodikToken("typed-by-hand")

        prefs.clearAccount()

        assertEquals("typed-by-hand", prefs.kodikToken.first())
    }

    @Test
    fun `the account is known only once both the id and the nickname are stored`() = runTest(dispatcher) {
        assertNull(prefs.account.first())

        prefs.setUserId(42)
        // An id on its own is an account nobody can name yet: whoami has not answered, or this
        // install predates the screen that shows it.
        assertNull(prefs.account.first())

        prefs.setAccountProfile("kaeru", "https://shikimori.io/avatar.png")

        assertEquals(Account(42, "kaeru", "https://shikimori.io/avatar.png"), prefs.account.first())
    }

    @Test
    fun `an account with no avatar is still an account`() = runTest(dispatcher) {
        prefs.setUserId(7)
        prefs.setAccountProfile("kaeru", null)

        assertEquals(Account(7, "kaeru", null), prefs.account.first())
    }

    @Test
    fun `signing out forgets who was signed in`() = runTest(dispatcher) {
        prefs.setUserId(42)
        prefs.setAccountProfile("kaeru", "https://shikimori.io/avatar.png")

        prefs.clearAccount()

        assertNull(prefs.account.first())
        assertNull(store.data.first()[stringPreferencesKey("account_nickname")])
        assertNull(store.data.first()[stringPreferencesKey("account_avatar")])
    }

    @Test
    fun `the download policy starts at the defaults the spec names`() = runTest(dispatcher) {
        assertEquals(DownloadPolicy.DEFAULT, prefs.downloadPolicy.first())
    }

    @Test
    fun `a download policy survives the round trip`() = runTest(dispatcher) {
        val chosen = DownloadPolicy(
            limitBytes = 20L * 1024 * 1024 * 1024,
            wifiOnly = false,
            deleteWatched = true,
            quality = Quality.P480,
        )

        prefs.setDownloadPolicy(chosen)

        assertEquals(chosen, prefs.downloadPolicy.first())
    }

    @Test
    fun `no limit and no chosen height are stored as sentinels, not as absent keys`() = runTest(dispatcher) {
        // An absent key means «never set» and reads back as the default 5 GB at 720p, so
        // «без лимита» and «как при просмотре» need values of their own to be remembered at all.
        prefs.setDownloadPolicy(DownloadPolicy.DEFAULT.copy(limitBytes = null, quality = null))

        assertEquals(-1L, store.data.first()[longPreferencesKey("download_limit_bytes")])
        assertEquals(0, store.data.first()[intPreferencesKey("download_quality")])

        val read = prefs.downloadPolicy.first()
        assertNull(read.limitBytes)
        assertNull(read.quality)
    }

    @Test
    fun `the policy is written under the documented keys`() = runTest(dispatcher) {
        prefs.setDownloadPolicy(
            DownloadPolicy(2L * 1024 * 1024 * 1024, wifiOnly = false, deleteWatched = true, quality = Quality.P360),
        )
        val stored = store.data.first()

        assertEquals(2L * 1024 * 1024 * 1024, stored[longPreferencesKey("download_limit_bytes")])
        assertEquals(false, stored[booleanPreferencesKey("download_wifi_only")])
        assertEquals(true, stored[booleanPreferencesKey("download_delete_watched")])
        assertEquals(360, stored[intPreferencesKey("download_quality")])
    }

    @Test
    fun `a height this build no longer offers reads as no chosen height`() = runTest(dispatcher) {
        store.edit { it[intPreferencesKey("download_quality")] = 1440 }

        assertNull(prefs.downloadPolicy.first().quality)
    }

    // --- the two notes the download engine keeps between launches -------------------------------

    @Test
    fun `a promised deletion is remembered until it is kept`() = runTest(dispatcher) {
        prefs.record(DownloadedEpisode(100, 4))
        prefs.record(DownloadedEpisode(100, 5))

        assertEquals(setOf(DownloadedEpisode(100, 4), DownloadedEpisode(100, 5)), prefs.pending())

        prefs.forget(DownloadedEpisode(100, 4))

        assertEquals(setOf(DownloadedEpisode(100, 5)), prefs.pending())

        prefs.forgetAll()

        assertEquals(emptySet<DownloadedEpisode>(), prefs.pending())
    }

    /** A stored row nothing can read is dropped: it names an episode nobody can act on anyway. */
    @Test
    fun `a promise written in a spelling this build does not know is ignored`() = runTest(dispatcher) {
        store.edit { it[stringSetPreferencesKey("download_pending_removals")] = setOf("100:4", "rubbish", "7") }

        assertEquals(setOf(DownloadedEpisode(100, 4)), prefs.pending())
    }

    @Test
    fun `a download the network stranded is remembered until something picks it up`() = runTest(dispatcher) {
        prefs.recordStranded("100:4:609:720")
        prefs.recordStranded("100:5:609:720")

        assertEquals(setOf("100:4:609:720", "100:5:609:720"), prefs.stranded())

        prefs.forgetStranded("100:4:609:720")

        assertEquals(setOf("100:5:609:720"), prefs.stranded())
    }

    /** Both notes are about files on this device, so they outlive whoever was signed in. */
    @Test
    fun `both download notes survive a sign-out`() = runTest(dispatcher) {
        prefs.record(DownloadedEpisode(100, 4))
        prefs.recordStranded("100:5:609:720")
        prefs.setUserId(42)

        prefs.clear()

        assertEquals(setOf(DownloadedEpisode(100, 4)), prefs.pending())
        assertEquals(setOf("100:5:609:720"), prefs.stranded())
    }

    @Test
    fun `download settings belong to the device and survive a sign-out`() = runTest(dispatcher) {
        // An episode already on the phone is not a fact about who is signed in, and neither is
        // the rule that put it there.
        prefs.setDownloadPolicy(DownloadPolicy.DEFAULT.copy(wifiOnly = false, limitBytes = null))
        prefs.setUserId(42)

        prefs.clearAccount()

        val read = prefs.downloadPolicy.first()
        assertFalse(read.wifiOnly)
        assertNull(read.limitBytes)
    }
}
