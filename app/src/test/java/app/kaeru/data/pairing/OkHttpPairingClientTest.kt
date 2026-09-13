package app.kaeru.data.pairing

import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.di.PairingModule
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.repository.MOBILE_REDIRECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpPairingClientTest {
    private val server = MockWebServer()
    private val provided = PairingModule.pairingHttpClient()
    private lateinit var client: OkHttpPairingClient

    private val television = PairingRequest("192.168.1.7", 41234, "nonce-1", "Гостиная")

    @Before
    fun setUp() {
        server.start()
        // Keeps the production client — its timeouts, its cleartext-only connection spec — and
        // rewrites only where the request lands, so the address check still sees a private one.
        val routed = provided.newBuilder().addInterceptor { chain ->
            val url = server.url(chain.request().url.encodedPath)
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()
        client = OkHttpPairingClient(routed, shikimoriJson(), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the television is sent the nonce it issued, a fresh code and the redirect it was got with`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        assertTrue(client.send(television, "fresh-code", MOBILE_REDIRECT).isSuccess)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pair", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(
            """{"nonce":"nonce-1","code":"fresh-code","redirectUri":"kaeru://oauth"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `a television that turns the code away is reported as a refusal, not as a dead network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"expired"}"""))

        val failure = client.send(television, "stale-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.REFUSED, (failure as PairingFailed).reason)
    }

    @Test
    fun `a television nothing answers for is reported as unreachable`() = runTest {
        server.shutdown()

        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.UNREACHABLE, (failure as PairingFailed).reason)
    }

    @Test
    fun `an address outside the home network is never contacted at all`() = runTest {
        val elsewhere = PairingRequest("8.8.8.8", 80, "nonce-1", "Not a television")

        val failure = client.send(elsewhere, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.BAD_LINK, (failure as PairingFailed).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the request gives up rather than hanging while the phone shows a spinner`() {
        assertEquals(5_000, provided.connectTimeoutMillis)
        assertEquals(5_000, provided.writeTimeoutMillis)
        // Longer than the rest on purpose: this read waits out the television's own round trip to
        // Shikimori, so five seconds would report a failure for a sign-in that in fact succeeded.
        assertEquals(20_000, provided.readTimeoutMillis)
        assertEquals(30_000, provided.callTimeoutMillis)
    }

    @Test
    fun `the pairing client can only ever speak to a local television in the clear`() {
        assertEquals(listOf(okhttp3.ConnectionSpec.CLEARTEXT), provided.connectionSpecs)
        assertTrue(provided.interceptors.isEmpty())
        assertTrue("a one-time code must not be sent twice on its own", !provided.retryOnConnectionFailure)
    }

    @Test
    fun `the port the television named is the port that is dialled`() = runTest {
        // The interceptor above rewrites the destination, so the untouched url is read from a call
        // the production client builds and then refuses to send.
        val url = OkHttpPairingClient(
            OkHttpClient.Builder().connectTimeout(1, TimeUnit.MILLISECONDS).build(),
            shikimoriJson(),
            Dispatchers.IO,
        ).pairUrl(television)
        assertEquals("http://192.168.1.7:41234/pair", url.toString())
    }
}
