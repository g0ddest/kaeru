package app.kaeru.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The nickname and the face beside it, and the one rule that matters: a cached name is worth more
 * than an error, so a settings screen opened on a train shows who is signed in anyway.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesAccountRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private lateinit var prefs: AppPreferences
    private lateinit var repo: PreferencesAccountRepository

    @Before
    fun setUp() {
        server.start()
        prefs = AppPreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") },
        )
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriApi::class.java)
        repo = PreferencesAccountRepository(prefs, api)
    }

    @After
    fun tearDown() {
        server.shutdown()
        storeScope.cancel()
    }

    private fun whoami(body: String) = server.enqueue(
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body),
    )

    @Test
    fun `refresh stores the nickname and the avatar of the account this device is signed in as`() =
        runTest {
            prefs.setUserId(42)
            whoami("""{"id":42,"nickname":"kaeru","avatar":"https://shikimori.io/a.png"}""")

            val result = repo.refresh()

            assertTrue(result.isSuccess)
            assertEquals(Account(42, "kaeru", "https://shikimori.io/a.png"), repo.account.first())
        }

    @Test
    fun `a whoami about somebody else is not written down`() = runTest {
        prefs.setUserId(42)
        prefs.setAccountProfile("kaeru", null)
        whoami("""{"id":7,"nickname":"someone-else"}""")

        repo.refresh()

        assertEquals(Account(42, "kaeru", null), repo.account.first())
    }

    @Test
    fun `a failed refresh leaves the cached account alone`() = runTest {
        prefs.setUserId(42)
        prefs.setAccountProfile("kaeru", "https://shikimori.io/a.png")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.refresh()

        assertTrue(result.isFailure)
        assertEquals(Account(42, "kaeru", "https://shikimori.io/a.png"), repo.account.first())
    }

    @Test
    fun `nobody signed in is nobody, whatever the network says`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repo.refresh()

        assertTrue(result.isFailure)
        assertNull(repo.account.first())
    }
}
