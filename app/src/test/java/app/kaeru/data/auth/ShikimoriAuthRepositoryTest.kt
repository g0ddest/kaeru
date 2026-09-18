package app.kaeru.data.auth

import app.cash.turbine.test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.AuthInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.UnconfiguredOAuthApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.SignInUnavailable
import app.kaeru.domain.model.Account
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ShikimoriAuthRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: KaeruDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var api: ShikimoriApi
    private val server = MockWebServer()
    private val store = InMemoryTokenStore()
    private lateinit var oauth: ShikimoriOAuthApi
    private lateinit var repo: ShikimoriAuthRepository
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server.start()
        oauth = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriOAuthApi::class.java)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
        prefs = AppPreferences(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") })
        session = AccountSession(store, prefs, db)
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriApi::class.java)
        repo = ShikimoriAuthRepository(oauth, api, session, prefs, "cid", clock)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `authorize url contains client id redirect and scope`() {
        val url = repo.authorizeUrl(MOBILE_REDIRECT).toHttpUrl()
        assertEquals("https://shikimori.io/oauth/authorize", "${url.scheme}://${url.host}${url.encodedPath}")
        assertEquals("cid", url.queryParameter("client_id"))
        assertEquals(MOBILE_REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("user_rates", url.queryParameter("scope"))
    }

    @Test
    fun `authorize url carries a fresh unguessable state per attempt`() {
        val first = requireNotNull(repo.authorizeUrl(MOBILE_REDIRECT).toHttpUrl().queryParameter("state"))
        val second = requireNotNull(repo.authorizeUrl(MOBILE_REDIRECT).toHttpUrl().queryParameter("state"))
        assertTrue(first.length >= 32)
        assertNotEquals(first, second)
    }

    @Test
    fun `callback with a matching state signs the account in`() = runTest {
        enqueueTokens()
        val state = pendingState()
        assertTrue(repo.exchangeRedirectCode("abc", state).isSuccess)
        assertEquals(42L, store.get()?.userId)
    }

    @Test
    fun `callback with a wrong or missing state never reaches the oauth api`() = runTest {
        pendingState()
        assertRejected(repo.exchangeRedirectCode("attacker", "not-the-state"))
        pendingState()
        assertRejected(repo.exchangeRedirectCode("attacker", null))
        assertEquals(0, server.requestCount)
        assertNull(store.get())
    }

    @Test
    fun `callback without an authorization started from this app is rejected`() = runTest {
        assertRejected(repo.exchangeRedirectCode("attacker", "guessed"))
        assertEquals(0, server.requestCount)
        assertNull(store.get())
    }

    @Test
    fun `a state is single use so a replayed callback is rejected`() = runTest {
        enqueueTokens()
        val state = pendingState()
        assertTrue(repo.exchangeRedirectCode("abc", state).isSuccess)
        repo.logout()
        assertRejected(repo.exchangeRedirectCode("abc", state))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `callback while an account is signed in cannot switch accounts`() = runTest {
        enqueueTokens()
        assertTrue(repo.exchangeRedirectCode("abc", pendingState()).isSuccess)
        val before = store.get()
        val replay = pendingState()
        assertRejected(repo.exchangeRedirectCode("attacker", replay))
        assertEquals(before, store.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `blank callback code is rejected without an exchange`() = runTest {
        assertRejected(repo.exchangeRedirectCode("", pendingState()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `typed OOB code is exchanged without a state`() = runTest {
        enqueueTokens()
        assertTrue(repo.exchangeTypedCode("  typed-code  ").isSuccess)
        assertEquals(
            "grant_type=authorization_code&client_id=cid&code=typed-code&redirect_uri=urn%3Aietf%3Awg%3Aoauth%3A2.0%3Aoob",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `authorize url encodes reserved characters in client id and redirect`() {
        val custom = ShikimoriAuthRepository(oauth, api, session, prefs, "id&scope=other+value", clock)
        val url = custom.authorizeUrl("kaeru://oauth?value=a&other=b+c").toHttpUrl()
        assertEquals("id&scope=other+value", url.queryParameter("client_id"))
        assertEquals("kaeru://oauth?value=a&other=b+c", url.queryParameter("redirect_uri"))
        assertEquals(listOf("user_rates"), url.queryParameterValues("scope"))
    }

    @Test
    fun `authorize url supports OOB redirect`() {
        assertEquals(OOB_REDIRECT, repo.authorizeUrl(OOB_REDIRECT).toHttpUrl().queryParameter("redirect_uri"))
    }

    @Test
    fun `exchange code stores tokens and flips isLoggedIn`() = runTest {
        enqueueTokens()
        repo.isLoggedIn.test {
            assertEquals(false, awaitItem())
            assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)
            assertEquals(true, awaitItem())
        }
        assertEquals(AuthTokens("acc", "ref", 87_400, 42), store.get())
        val request = server.takeRequest()
        assertEquals("/oauth/token", request.path)
        assertEquals("POST", request.method)
        assertEquals("grant_type=authorization_code&client_id=cid&code=abc&redirect_uri=kaeru%3A%2F%2Foauth", request.body.readUtf8())
        val identity = server.takeRequest()
        assertEquals("/api/users/whoami", identity.path)
        assertEquals("Bearer acc", identity.getHeader("Authorization"))
    }

    @Test
    fun `signing in remembers the nickname and avatar the settings screen shows`() = runTest {
        enqueueTokens("""{"id":42,"nickname":"frog","avatar":"https://shikimori.io/frog.png"}""")

        assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)

        // The same `whoami` that verifies the identity also names it: asking twice for one sign-in
        // would be a second round trip for an answer already in hand.
        assertEquals(Account(42, "frog", "https://shikimori.io/frog.png"), prefs.account.first())
    }

    @Test
    fun `a nickname that cannot be written down does not fail a sign-in that already happened`() =
        runTest {
            // The token store and the preference store are two different files, so the second can
            // fail on its own. By the time it is written the sign-in has committed.
            val unwritable = AppPreferences(object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                    throw IOException("no space left on device")
            })
            val repo = ShikimoriAuthRepository(oauth, api, session, unwritable, "cid", clock)
            enqueueTokens()

            assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)

            assertTrue(repo.isLoggedIn.first())
            // The name is simply missing until the settings screen asks `whoami` again.
            assertNull(prefs.account.first())
        }

    @Test
    fun `a relative avatar path is stored as a url something can actually load`() = runTest {
        enqueueTokens("""{"id":42,"nickname":"frog","avatar":"/system/users/x160/42.png"}""")

        assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)

        assertEquals(
            Account(42, "frog", "https://shikimori.io/system/users/x160/42.png"),
            prefs.account.first(),
        )
    }

    @Test
    fun `signing out forgets the nickname along with the rest of the account`() = runTest {
        enqueueTokens("""{"id":42,"nickname":"frog","avatar":"https://shikimori.io/frog.png"}""")
        assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)

        repo.logout()

        assertNull(prefs.account.first())
    }

    @Test
    fun `exchange code form encodes client id codes and OOB redirect`() = runTest {
        enqueueTokens()
        val custom = ShikimoriAuthRepository(oauth, api, session, prefs, "c+id", clock)
        assertTrue(custom.exchangeCode("a+b&c", OOB_REDIRECT).isSuccess)
        assertEquals("grant_type=authorization_code&client_id=c%2Bid&code=a%2Bb%26c&redirect_uri=urn%3Aietf%3Awg%3Aoauth%3A2.0%3Aoob", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `without a token proxy the exchange fails and nobody is signed in`() = runTest {
        val offline = ShikimoriAuthRepository(UnconfiguredOAuthApi, api, session, prefs, "cid", clock)

        val result = offline.exchangeCode("abc", MOBILE_REDIRECT)

        assertTrue(result.exceptionOrNull() is SignInUnavailable)
        assertEquals(0, server.requestCount)
        assertNull(store.get())
        assertEquals(false, offline.isLoggedIn.first())
    }

    @Test
    fun `failed exchange returns failure without logging in`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        repo.isLoggedIn.test {
            assertEquals(false, awaitItem())
            assertTrue(repo.exchangeCode("bad", MOBILE_REDIRECT).isFailure)
            expectNoEvents()
        }
        assertNull(store.get())
    }

    @Test
    fun `logout clears tokens and emits logged out without duplicate refresh emissions`() = runTest {
        prefs.setUserId(42)
        store.set(AuthTokens("a", "r", 5, 42))
        repo.isLoggedIn.test {
            assertEquals(true, awaitItem())
            store.set(AuthTokens("b", "r2", 6, 42))
            expectNoEvents()
            repo.logout()
            assertEquals(false, awaitItem())
        }
        assertNull(store.get())
    }

    @Test
    fun `a code paired from a phone is exchanged with the redirect that phone used`() = runTest {
        enqueueTokens()
        assertTrue(repo.exchangePairedCode("  paired-code  ", MOBILE_REDIRECT).isSuccess)
        assertEquals(
            "grant_type=authorization_code&client_id=cid&code=paired-code&redirect_uri=kaeru%3A%2F%2Foauth",
            server.takeRequest().body.readUtf8(),
        )
        assertEquals(42L, store.get()?.userId)
    }

    @Test
    fun `a paired code needs no state because the nonce on the television was the confirmation`() = runTest {
        enqueueTokens()
        assertTrue(repo.exchangePairedCode("paired-code", MOBILE_REDIRECT).isSuccess)
    }

    @Test
    fun `a pairing cannot redirect the token request anywhere this app does not own`() = runTest {
        assertRejected(repo.exchangePairedCode("code", "https://attacker.example/collect"))
        assertRejected(repo.exchangePairedCode("code", ""))
        assertEquals(0, server.requestCount)
        assertNull(store.get())
    }

    @Test
    fun `a pairing carrying no code never reaches the oauth api`() = runTest {
        assertRejected(repo.exchangePairedCode("   ", MOBILE_REDIRECT))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a pairing cannot switch the account a television is already signed into`() = runTest {
        enqueueTokens()
        assertTrue(repo.exchangePairedCode("first", MOBILE_REDIRECT).isSuccess)
        val before = store.get()
        assertRejected(repo.exchangePairedCode("second", MOBILE_REDIRECT))
        assertEquals(before, store.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an authorization built for a pairing arms nothing on this phone`() = runTest {
        val pairing = repo.pairingAuthorization()
        val url = pairing.url.toHttpUrl()
        assertEquals(MOBILE_REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals(pairing.state, url.queryParameter("state"))
        assertTrue(pairing.state.length >= 32)

        // The code this URL produces is going to a television, so nothing here waits for a
        // callback: one echoing that state is as unsolicited as any other.
        assertRejected(repo.exchangeRedirectCode("attacker", pairing.state))
        assertEquals(0, server.requestCount)
        assertNull(store.get())
    }

    @Test
    fun `every pairing authorization carries a state of its own`() {
        assertNotEquals(repo.pairingAuthorization().state, repo.pairingAuthorization().state)
    }

    @Test
    fun `a pairing authorization does not disturb a sign-in already in flight`() = runTest {
        enqueueTokens()
        val armed = pendingState()
        repo.pairingAuthorization()
        assertTrue(repo.exchangeRedirectCode("abc", armed).isSuccess)
    }

    private fun pendingState(): String =
        requireNotNull(repo.authorizeUrl(MOBILE_REDIRECT).toHttpUrl().queryParameter("state"))

    private fun assertRejected(result: Result<Unit>) {
        assertTrue(result.exceptionOrNull() is AuthCallbackRejected)
    }

    private fun enqueueTokens(whoami: String = """{"id":42,"nickname":"frog"}""") {
        server.enqueue(MockResponse().setBody("""{"access_token":"acc","token_type":"Bearer","expires_in":86400,"refresh_token":"ref","scope":"user_rates","created_at":1757600000}"""))
        server.enqueue(MockResponse().setBody(whoami))
    }
}
