package app.kaeru.data.pairing

import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.repository.MOBILE_REDIRECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import app.kaeru.test.MutableClock

class SocketPairingServerTest {
    private val now = Instant.parse("2026-09-13T20:00:00Z")
    private val clock = MutableClock(now)
    private val lan = FakeLanAddresses("192.168.1.7")
    // Compressed clocks: the slow-client test below has to outlast the accept deadline, and a
    // test that waits out the shipped five seconds is a test somebody deletes.
    private val timeouts = PairingTimeouts(acceptMs = 400, readMs = 200)
    private val server = SocketPairingServer(shikimoriJson(), clock, lan, timeouts, Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val exchanges = mutableListOf<Pair<String, String>>()
    private var exchangeResult: Result<Unit> = Result.success(Unit)

    /** Thrown by the exchange rather than returned by it, for the one test about a handler that blows up. */
    private var exchangeThrows: Throwable? = null

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
            exchangeThrows?.let { throw it }
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
    fun `an exchange that throws does not take the offer down with it`() = runTest {
        // Anything escaping the handler used to end the accept loop and leave the port bound with
        // nothing listening on it — a QR on screen pointing at a socket that would never answer.
        val port = listen().port
        exchangeThrows = IllegalStateException("something nobody anticipated")
        // And the phone is told so rather than hung up on: a connection that closes with nothing
        // on it reads as «that television is not there», and this one is there.
        val (status, answer) = post(port, body())
        assertEquals(500, status)
        assertTrue(answer, answer.contains("server_error"))
        assertEquals(1, exchanges.size)

        // The same offer, still live: the nonce was never spent, so the next phone is served.
        exchangeThrows = null
        assertEquals(200 to """{"ok":true}""", post(port, body(code = "second-code")))
        assertEquals(listOf("fresh-code", "second-code"), exchanges.map { it.first })
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
    fun `a fresh port is opened after the last one was stopped`() = runTest {
        val first = listen().port
        server.stop()
        val second = listen(PairingSession("nonce-2", now, Duration.ofMinutes(5))).port
        assertTrue(runCatching { post(first, body()) }.isFailure)
        assertEquals(200, post(second, body(nonce = "nonce-2")).first)
    }

    @Test
    fun `a stop landing in the middle of a start leaves nothing bound`() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val slowLan = object : LanAddresses {
            override fun siteLocalIpv4(): String? {
                entered.countDown()
                released.await()
                return "192.168.1.7"
            }
        }
        val racing = SocketPairingServer(shikimoriJson(), clock, slowLan, timeouts, Dispatchers.IO)
        val started = async(Dispatchers.IO) {
            racing.start(PairingSession("nonce-1", now, Duration.ofMinutes(5))) { _, _ -> Result.success(Unit) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        racing.stop()
        released.countDown()

        val failure = started.await().exceptionOrNull()
        assertEquals(PairingFailureReason.SUPERSEDED, (failure as PairingFailed).reason)
        racing.stop()
    }

    @Test
    fun `a caller that connects and says nothing is dropped, and the phone behind it gets through`() =
        runTest {
            val port = listen().port
            Socket().use { silent ->
                silent.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                // The accept loop is single-file on purpose, so this POST cannot even be accepted
                // until the silent connection above has been given up on.
                assertEquals(200 to """{"ok":true}""", post(port, body()))
            }
            assertEquals(1, exchanges.size)
        }

    /**
     * The one a per-read timeout cannot catch: every byte arrives well inside it, and only a
     * deadline on the whole request ends the connection. Thirteen kilobytes of budget at one byte
     * per timeout is a day and a half of holding the only accept loop this server has.
     */
    @Test
    fun `a caller that dribbles a byte at a time is cut off when the request runs out of time`() =
        runTest {
            val port = listen().port
            Socket().use { slow ->
                slow.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                val dribbler = thread(isDaemon = true) {
                    runCatching {
                        val out = slow.getOutputStream()
                        repeat(10_000) {
                            out.write('P'.code)
                            out.flush()
                            Thread.sleep(timeouts.readMs / 4L)
                        }
                    }
                }
                val startedAt = System.nanoTime()
                assertEquals(200, post(port, body()).first)
                val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
                dribbler.interrupt()
                assertTrue("the phone waited ${waitedMs}ms behind a slow caller", waitedMs < 3_000)
            }
            assertEquals(1, exchanges.size)
        }

    /** And the backstop for a read timeout that outlasts the deadline: the watchdog, on its own. */
    @Test
    fun `a connection whose reads have not timed out yet is still closed at the deadline`() = runTest {
        val patient = SocketPairingServer(
            shikimoriJson(),
            clock,
            lan,
            PairingTimeouts(acceptMs = 400, readMs = 30_000),
            Dispatchers.IO,
        )
        val port = patient.start(PairingSession("nonce-1", now, Duration.ofMinutes(5))) { code, redirect ->
            synchronized(exchanges) { exchanges += code to redirect }
            Result.success(Unit)
        }.getOrThrow().port
        try {
            Socket().use { silent ->
                silent.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                val startedAt = System.nanoTime()
                assertEquals(200, post(port, body()).first)
                val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertTrue("the phone waited ${waitedMs}ms behind a silent caller", waitedMs < 5_000)
            }
        } finally {
            patient.stop()
        }
        assertEquals(1, exchanges.size)
    }

    @Test
    fun `two phones racing one nonce spend it exactly once`() = runTest {
        val inExchange = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = server.start(PairingSession("nonce-1", now, Duration.ofMinutes(5))) { code, redirect ->
            synchronized(exchanges) { exchanges += code to redirect }
            inExchange.countDown()
            release.await()
            Result.success(Unit)
        }.getOrThrow().port

        val first = async(Dispatchers.IO) { post(port, body(code = "first")) }
        assertTrue(inExchange.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { post(port, body(code = "second")) }
        release.countDown()

        val answers = listOf(first.await(), second.await())
        assertEquals(listOf(200, 410), answers.map { it.first }.sorted())
        assertEquals(1, exchanges.size)
    }

    @Test
    fun `a wrong nonce learns nothing about the offer it guessed at`() = runTest {
        val port = listen().port
        assertEquals(200, post(port, body()).first)

        // The pairing is spent, but a caller that never read the code off the screen is told only
        // that its nonce is wrong — never that there was a live offer here, or that it is gone.
        val (code, answer) = post(port, body(nonce = "guessed"))
        assertEquals(409, code)
        assertTrue(answer, answer.contains("nonce_mismatch"))
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
