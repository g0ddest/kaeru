package app.kaeru.data.viewsync

import app.kaeru.domain.viewsync.SyncFailure
import app.kaeru.shared.ApiException
import app.kaeru.shared.domain.sync.SyncPosition
import app.kaeru.shared.domain.sync.SyncTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The `/sync` wire and the client that speaks it, against a worker played by a local server. */
class RelaySyncApiTest {
    private val server = MockWebServer()

    /** A session that hands out [token], and a fresh one once after a 401, as the Shikimori session does. */
    private class FakeSession(var token: String = "access") : BearerSession {
        var refreshes = 0

        override suspend fun <T> withBearer(call: suspend (token: String) -> T): T = try {
            call(token)
        } catch (unauthorized: ApiException) {
            if (unauthorized.status != 401) throw unauthorized
            refreshes++
            token = "fresh"
            call(token)
        }
    }

    private val session = FakeSession()
    private lateinit var api: RelaySyncApi

    @Before
    fun setUp() {
        server.start()
        val relay = "ws://${server.hostName}:${server.port}"
        api = RelaySyncApi(relay, OkHttpClient(), session, Dispatchers.IO)
    }

    @After
    fun tearDown() = server.shutdown()

    // --- the wire (the codec itself is tested in shared: SyncWireTest, sync-vectors.json) --------

    @Test
    fun `an answer without titles is a parser failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":"x"}"""))
        try {
            api.get()
            fail("expected a failure")
        } catch (failure: SyncFailure) {
            assertEquals(SyncFailure.Kind.PARSER, failure.kind)
        }
    }

    @Test
    fun `the relay address becomes the sync endpoint over https`() {
        assertEquals("https://relay.example.dev/sync", RelaySyncApi.endpointOf("wss://relay.example.dev")?.toString())
        assertEquals("https://relay.example.dev/sync", RelaySyncApi.endpointOf("wss://relay.example.dev/w/x?y=1")?.toString())
        assertEquals("http://10.0.2.2:8787/sync", RelaySyncApi.endpointOf("ws://10.0.2.2:8787")?.toString())
        assertNull(RelaySyncApi.endpointOf(""))
        assertNull(RelaySyncApi.endpointOf("ftp://relay"))
    }

    // --- the client -----------------------------------------------------------------------------

    @Test
    fun `a read sends the bearer and nothing else of the account`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"titles":{"5":{"gone":7}}}"""))

        val titles = api.get()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/sync", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals(mapOf("5" to SyncTitle(gone = 7)), titles)
    }

    @Test
    fun `a write posts the batch as json and reads the merged document back`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"titles":{"5":{"eps":{"1":{"p":1,"d":2,"at":3}}},"9":{"gone":4}}}"""))

        val answer = api.post(mapOf("5" to SyncTitle(eps = mapOf("1" to SyncPosition(1, 2, 3)))))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("titles"), sent.keys)
        assertEquals(setOf("5", "9"), answer.keys)
    }

    @Test
    fun `an expired token is refreshed once and the request repeated`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"sign_in"}"""))
        server.enqueue(MockResponse().setBody("""{"titles":{}}"""))

        api.get()

        assertEquals(1, session.refreshes)
        assertEquals("Bearer access", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the worker's refusals are named`() = runBlocking {
        val cases = listOf(
            MockResponse().setResponseCode(502).setBody("""{"error":"unavailable"}""") to SyncFailure.Kind.UNAVAILABLE,
            MockResponse().setResponseCode(400).setBody("""{"error":"parameters"}""") to SyncFailure.Kind.PARAMETERS,
            MockResponse().setResponseCode(429).setBody("Too many requests") to SyncFailure.Kind.THROTTLED,
            MockResponse().setResponseCode(500).setBody("oops") to SyncFailure.Kind.UNKNOWN,
        )
        for ((response, kind) in cases) {
            server.enqueue(response)
            try {
                api.get()
                fail("expected $kind")
            } catch (failure: SyncFailure) {
                assertEquals(kind, failure.kind)
            }
        }
    }

    @Test
    fun `a request that never got an answer is offline`() = runBlocking {
        server.shutdown()
        try {
            api.get()
            fail("expected a failure")
        } catch (failure: SyncFailure) {
            assertEquals(SyncFailure.Kind.OFFLINE, failure.kind)
        }
    }

    @Test
    fun `without a session nothing is sent`() = runBlocking {
        session.token = ""
        try {
            api.get()
            fail("expected a failure")
        } catch (failure: SyncFailure) {
            assertEquals(SyncFailure.Kind.SIGNED_OUT, failure.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a build without a relay dials nothing`() = runBlocking {
        val none = RelaySyncApi("", OkHttpClient(), session, Dispatchers.IO)
        try {
            none.get()
            fail("expected a failure")
        } catch (failure: SyncFailure) {
            assertEquals(SyncFailure.Kind.UNAVAILABLE, failure.kind)
        }
        assertEquals(0, server.requestCount)
    }
}
