package app.kaeru.data.pairing

import app.kaeru.di.NetworkModule
import app.kaeru.di.PairingModule
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.repository.MOBILE_REDIRECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class SocketPairingClientTest {
    private val server = MockWebServer()

    /**
     * The address in the link is checked against the private ranges; the address actually dialled
     * is the loopback one a `MockWebServer` binds. Separating the two is the whole reason
     * [PairingAddresses] exists — the check still sees what a television would really advertise.
     */
    private val timeouts = PairingTimeouts(connectMs = 1_000, requestMs = 1_000)
    private val client = SocketPairingClient(
        NetworkModule.json(),
        timeouts,
        { InetSocketAddress("127.0.0.1", server.port) },
        Dispatchers.IO,
    )

    private val television = PairingRequest("192.168.1.7", 41_234, "nonce-1", "Гостиная")

    @Before
    fun setUp() {
        server.start()
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
        assertEquals("POST /pair HTTP/1.1", request.requestLine)
        assertEquals("192.168.1.7:41234", request.getHeader("Host"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("close", request.getHeader("Connection"))
        assertEquals(
            """{"nonce":"nonce-1","code":"fresh-code","redirectUri":"kaeru://oauth"}""",
            request.body.readUtf8(),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a television that turns the code away is reported as a refusal, not as a dead network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"expired"}"""))

        val failure = client.send(television, "stale-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.REFUSED, (failure as PairingFailed).reason)
        // A code is good for one exchange. Nothing but the person tapping «Повторить» resends it.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a cheerful 200 from something that is not a television is not a sign-in`() = runTest {
        server.enqueue(MockResponse().setBody("<html>router login</html>"))

        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.REFUSED, (failure as PairingFailed).reason)
    }

    @Test
    fun `a redirect is an answer, not an instruction`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/pair"))

        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.REFUSED, (failure as PairingFailed).reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an answer too large to be a pairing is not taken for one`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(100_000)))

        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.REFUSED, (failure as PairingFailed).reason)
    }

    @Test
    fun `a television nothing answers for is reported as unreachable`() = runTest {
        server.shutdown()

        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.UNREACHABLE, (failure as PairingFailed).reason)
    }

    @Test
    fun `a television that accepts and then says nothing gives up rather than hanging`() = runTest {
        // Nothing enqueued: the connection is accepted and never answered.
        val started = System.nanoTime()
        val failure = client.send(television, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(PairingFailureReason.UNREACHABLE, (failure as PairingFailed).reason)
        assertTrue("gave up after ${tookMs}ms", tookMs < 10_000)
    }

    @Test
    fun `an address outside the home network is never contacted at all`() = runTest {
        val elsewhere = PairingRequest("8.8.8.8", 80, "nonce-1", "Not a television")

        val failure = client.send(elsewhere, "fresh-code", MOBILE_REDIRECT).exceptionOrNull()

        assertEquals(PairingFailureReason.BAD_LINK, (failure as PairingFailed).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the shipped clocks are the ones the protocol was reasoned about with`() {
        val shipped = PairingModule.pairingTimeouts()
        assertEquals(5_000, shipped.connectMs)
        // Longer than the rest on purpose: this read waits out the television's own round trip to
        // Shikimori, so five seconds would report a failure for a sign-in that in fact succeeded.
        assertEquals(20_000, shipped.requestMs)
        assertEquals(5_000L, shipped.acceptMs)
        assertEquals(2_000, shipped.readMs)
    }
}
