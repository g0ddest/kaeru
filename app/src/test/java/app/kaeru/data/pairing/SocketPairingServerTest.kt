package app.kaeru.data.pairing

import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.repository.MOBILE_REDIRECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import app.kaeru.test.MutableClock

class SocketPairingServerTest {
    private val now = Instant.parse("2026-09-13T20:00:00Z")
    private val clock = MutableClock(now)
    private val lan = FakeLanAddresses("192.168.1.7")
    private val server = SocketPairingServer(shikimoriJson(), clock, lan, Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val exchanges = mutableListOf<Pair<String, String>>()
    private var exchangeResult: Result<Unit> = Result.success(Unit)

    @After
    fun tearDown() {
        server.stop()
    }

    private class FakeLanAddresses(var address: String?) : LanAddresses {
        override fun siteLocalIpv4(): String? = address
    }

    private suspend fun listen(session: PairingSession = PairingSession("nonce-1", now, Duration.ofMinutes(5))) =
        server.start(session) { code, redirect ->
            synchronized(exchanges) { exchanges += code to redirect }
            exchangeResult
        }.getOrThrow()

    private fun body(nonce: String = "nonce-1", code: String = "fresh-code", redirect: String = MOBILE_REDIRECT) =
        """{"nonce":"$nonce","code":"$code","redirectUri":"$redirect"}"""

    private fun post(port: Int, payload: String, path: String = "/pair"): Pair<Int, String> {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            // Anything else answering on this port is a bind that should never have succeeded, and
            // says so here rather than as an unreadable diff of somebody else's response body.
            assertEquals(
                "answered by something that is not the pairing server",
                "application/json; charset=utf-8",
                response.header("Content-Type"),
            )
            response.code to (response.body?.string() ?: "")
        }
    }

    private fun get(port: Int, path: String = "/pair"): Pair<Int, String> =
        http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build())
            .execute().use { it.code to (it.body?.string() ?: "") }

    @Test
    fun `the endpoint it reports is the address a phone can reach it at`() = runTest {
        val endpoint = listen()
        assertEquals("192.168.1.7", endpoint.host)
        assertTrue(endpoint.port.toString(), endpoint.port in 1..65535)
    }

    @Test
    fun `a television with no address on a local network has nothing to offer`() = runTest {
        lan.address = null
        val failure = server.start(PairingSession("n", now)) { _, _ -> Result.success(Unit) }.exceptionOrNull()
        assertEquals(PairingFailureReason.NO_LOCAL_ADDRESS, (failure as PairingFailed).reason)
    }

    @Test
    fun `a code arriving with the right nonce is exchanged once and the next one is refused`() = runTest {
        val port = listen().port
        assertEquals(200 to """{"ok":true}""", post(port, body()))
        assertEquals(listOf("fresh-code" to MOBILE_REDIRECT), exchanges)

        val (code, answer) = post(port, body(code = "second-code"))
        assertEquals(410, code)
        assertTrue(answer, answer.contains("already_paired"))
        assertEquals(1, exchanges.size)
    }

    @Test
    fun `a nonce nobody read off this screen is refused without an exchange`() = runTest {
        val port = listen().port
        val (code, answer) = post(port, body(nonce = "guessed"))
        assertEquals(409, code)
        assertTrue(answer, answer.contains("nonce_mismatch"))
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `a nonce older than its five minutes is gone`() = runTest {
        val port = listen().port
        clock.advance(Duration.ofMinutes(5))
        val (code, answer) = post(port, body())
        assertEquals(410, code)
        assertTrue(answer, answer.contains("expired"))
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `an exchange the television could not finish leaves the nonce usable`() = runTest {
        val port = listen().port
        exchangeResult = Result.failure(IllegalStateException("shikimori is down"))
        val (code, answer) = post(port, body())
        assertEquals(409, code)
        assertTrue(answer, answer.contains("exchange_failed"))

        exchangeResult = Result.success(Unit)
        assertEquals(200 to """{"ok":true}""", post(port, body(code = "retried")))
        assertEquals(listOf("fresh-code" to MOBILE_REDIRECT, "retried" to MOBILE_REDIRECT), exchanges)
    }

    @Test
    fun `anything that is not a pairing post is answered without an exchange`() = runTest {
        val port = listen().port
        assertEquals(400, post(port, body(), path = "/").first)
        assertEquals(400, get(port).first)
        assertEquals(400, post(port, "not json at all").first)
        assertEquals(400, post(port, """{"nonce":"nonce-1"}""").first)
        assertEquals(400, post(port, body(code = "")).first)
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `a body too large to be a pairing is refused before it is read into memory`() = runTest {
        val port = listen().port
        val (code, _) = post(port, """{"nonce":"nonce-1","code":"${"x".repeat(8_000)}","redirectUri":"$MOBILE_REDIRECT"}""")
        assertEquals(400, code)
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `stopping closes the port so nothing is left listening on the network`() = runTest {
        val port = listen().port
        server.stop()
        val failed = runCatching { post(port, body()) }
        assertTrue(failed.toString(), failed.isFailure)
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `starting again replaces the offer rather than adding a second one`() = runTest {
        val first = listen().port
        val second = listen(PairingSession("nonce-2", now, Duration.ofMinutes(5))).port
        assertTrue(runCatching { post(first, body()) }.isFailure)
        assertEquals(200, post(second, body(nonce = "nonce-2")).first)
    }

    @Test
    fun `the answer says it is json so a phone can read the reason`() = runTest {
        val port = listen().port
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/pair")
            .post(body().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            assertEquals("application/json; charset=utf-8", response.header("Content-Type"))
        }
    }
}
