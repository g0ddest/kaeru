package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore(AuthTokens("old", "refresh-1", 0))
    private lateinit var client: OkHttpClient
    private lateinit var authenticator: TokenAuthenticator

    @Before
    fun setUp() {
        server.start()
        val oauth = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriOAuthApi::class.java)
        authenticator = TokenAuthenticator(
            store, oauth, "cid", "sec", Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
        )
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(store)).authenticator(authenticator).build()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `on 401 refreshes token once and retries with new bearer`() {
        server.enqueue(MockResponse().setResponseCode(401))
        enqueueTokens()
        server.enqueue(MockResponse().setBody("{}"))

        client.newCall(request()).execute().use { assertEquals(200, it.code) }

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals("application/x-www-form-urlencoded", refresh.getHeader("Content-Type"))
        assertEquals("grant_type=refresh_token&client_id=cid&client_secret=sec&refresh_token=refresh-1", refresh.body.readUtf8())
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals(AuthTokens("new", "refresh-2", 87_400), runBlocking { store.get() })
    }

    @Test
    fun `when refresh fails tokens are cleared and 401 is returned`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        client.newCall(request()).execute().use { assertEquals(401, it.code) }
        assertNull(runBlocking { store.get() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unauthorized refresh does not recurse`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        client.newCall(request()).execute().use { assertEquals(401, it.code) }
        assertEquals(2, server.requestCount)
        assertNull(runBlocking { store.get() })
    }

    @Test
    fun `oauth token endpoint itself is never retried or given a bearer`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val request = Request.Builder().url(server.url("/oauth/token"))
            .post(FormBody.Builder().add("a", "b").build()).build()
        client.newCall(request).execute().use { assertEquals(401, it.code) }
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("old", runBlocking { store.get() }?.accessToken)
    }

    @Test
    fun `retry rejected with 401 does not refresh a second time`() {
        server.enqueue(MockResponse().setResponseCode(401))
        enqueueTokens()
        server.enqueue(MockResponse().setResponseCode(401))
        client.newCall(request()).execute().use { assertEquals(401, it.code) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `missing tokens leave requests anonymous without refresh`() {
        runBlocking { store.set(null) }
        server.enqueue(MockResponse().setResponseCode(401))
        client.newCall(request()).execute().use { assertEquals(401, it.code) }
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `already rotated token is reused without another refresh`() {
        runBlocking { store.set(AuthTokens("rotated", "refresh-2", 87_400)) }
        val response = Response.Builder().request(request().newBuilder().header("Authorization", "Bearer old").build())
            .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
        assertEquals("Bearer rotated", authenticator.authenticate(null, response)?.header("Authorization"))
        assertEquals(0, server.requestCount)
    }

    private fun request() = Request.Builder().url(server.url("/api/users/whoami")).build()

    private fun enqueueTokens() {
        server.enqueue(MockResponse().setBody("""{"access_token":"new","token_type":"Bearer","expires_in":86400,"refresh_token":"refresh-2","scope":"user_rates","created_at":1757600000}"""))
    }
}
