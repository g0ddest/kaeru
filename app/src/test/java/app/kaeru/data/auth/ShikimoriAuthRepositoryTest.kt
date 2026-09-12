package app.kaeru.data.auth

import app.cash.turbine.test
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ShikimoriAuthRepositoryTest {
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
        repo = ShikimoriAuthRepository(oauth, store, "cid", "sec", clock)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `authorize url contains client id redirect and scope`() {
        assertEquals(
            "https://shikimori.one/oauth/authorize?client_id=cid&redirect_uri=kaeru%3A%2F%2Foauth&response_type=code&scope=user_rates",
            repo.authorizeUrl(MOBILE_REDIRECT),
        )
    }

    @Test
    fun `authorize url encodes reserved characters in client id and redirect`() {
        val custom = ShikimoriAuthRepository(oauth, store, "id&scope=other+value", "sec", clock)
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
        assertEquals(AuthTokens("acc", "ref", 87_400), store.get())
        val request = server.takeRequest()
        assertEquals("/oauth/token", request.path)
        assertEquals("POST", request.method)
        assertEquals("grant_type=authorization_code&client_id=cid&client_secret=sec&code=abc&redirect_uri=kaeru%3A%2F%2Foauth", request.body.readUtf8())
    }

    @Test
    fun `exchange code form encodes secrets codes and OOB redirect`() = runTest {
        enqueueTokens()
        val custom = ShikimoriAuthRepository(oauth, store, "c+id", "s&ec", clock)
        assertTrue(custom.exchangeCode("a+b&c", OOB_REDIRECT).isSuccess)
        assertEquals("grant_type=authorization_code&client_id=c%2Bid&client_secret=s%26ec&code=a%2Bb%26c&redirect_uri=urn%3Aietf%3Awg%3Aoauth%3A2.0%3Aoob", server.takeRequest().body.readUtf8())
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
        store.set(AuthTokens("a", "r", 5))
        repo.isLoggedIn.test {
            assertEquals(true, awaitItem())
            store.set(AuthTokens("b", "r2", 6))
            expectNoEvents()
            repo.logout()
            assertEquals(false, awaitItem())
        }
        assertNull(store.get())
    }

    private fun enqueueTokens() {
        server.enqueue(MockResponse().setBody("""{"access_token":"acc","token_type":"Bearer","expires_in":86400,"refresh_token":"ref","scope":"user_rates","created_at":1757600000}"""))
    }
}
