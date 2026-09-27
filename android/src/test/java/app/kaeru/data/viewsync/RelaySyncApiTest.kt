package app.kaeru.data.viewsync

import app.kaeru.domain.viewsync.SyncDub
import app.kaeru.domain.viewsync.SyncFailure
import app.kaeru.domain.viewsync.SyncPosition
import app.kaeru.domain.viewsync.SyncSecret
import app.kaeru.domain.viewsync.SyncTitle
import app.kaeru.shared.ApiException
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

    // --- the wire -------------------------------------------------------------------------------

    @Test
    fun `a batch carries milliseconds and leaves out what is absent`() {
        val body = SyncWire.body(
            mapOf(
                "5" to SyncTitle(
                    dub = SyncDub(610, "AniLibria.TV", 1_790_000_000_000),
                    eps = mapOf("3" to SyncPosition(861_000, 1_440_000, 1_790_000_000_001)),
                ),
                "6" to SyncTitle(gone = 1_790_000_000_002),
            ),
        )
        assertEquals(
            """{"titles":{"5":{"dub":{"id":610,"title":"AniLibria.TV","at":1790000000000},""" +
                """"eps":{"3":{"p":861000,"d":1440000,"at":1790000000001}}},"6":{"gone":1790000000002}}}""",
            body,
        )
    }

    @Test
    fun `an answer is read as far as it can be, the rest skipped`() {
        val titles = SyncWire.titles(
            """{"titles":{
                "5":{"dub":{"id":610,"title":"A","at":10},"eps":{"1":{"p":5,"d":9,"at":11},"x":{"p":1,"d":2,"at":3},"2":{"p":"5","d":9,"at":1}},"secret":{"on":true,"watched":3,"at":4}},
                "6":{"gone":12,"dub":{"id":1,"at":2}},
                "abc":{"gone":1},
                "7":"nonsense"
            }}""",
        )
        assertEquals(setOf("5", "6"), titles.keys)
        assertEquals(SyncDub(610, "A", 10), titles.getValue("5").dub)
        assertEquals(mapOf("1" to SyncPosition(5, 9, 11)), titles.getValue("5").eps)
        assertEquals(12L, titles.getValue("6").gone)
        assertNull(titles.getValue("6").dub)
        assertEquals(SyncSecret(true, 3, 4), titles.getValue("5").secret)
    }

    @Test
    fun `secret goes out and comes back as the worker writes it`() {
        val body = SyncWire.body(mapOf("5" to SyncTitle(secret = SyncSecret(on = true, watched = 3, at = 4))))
        assertEquals("""{"titles":{"5":{"secret":{"on":true,"watched":3,"at":4}}}}""", body)
        assertEquals(SyncSecret(true, 3, 4), SyncWire.titles(body).getValue("5").secret)
        val broken = SyncWire.titles("""{"titles":{"5":{"secret":{"on":"yes","watched":3,"at":4}}}}""")
        assertNull(broken.getValue("5").secret)
    }

    @Test
    fun `an answer without titles is a parser failure`() {
        try {
            SyncWire.titles("""{"error":"x"}""")
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
