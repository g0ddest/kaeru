package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.shared.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The session on the shared client: one refresh after a 401, one retry, and nothing that could end a session by accident. */
class ShikimoriSessionTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore(AuthTokens("old", "refresh-1", 0))
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC)
    private lateinit var api: SessionShikimoriApi

    @Before
    fun setUp() {
        server.start()
        api = SessionShikimoriApi(serverClient(server), store, clock)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tokens() = MockResponse().setBody(
        """{"access_token":"new","token_type":"Bearer","expires_in":86400,"refresh_token":"refresh-2","scope":"user_rates","created_at":1757600000}""",
    )

    private fun user() = MockResponse().setBody("""{"id":42,"nickname":"frog"}""")

    private fun unauthorized() = MockResponse().setResponseCode(401)

    /** The call fails with the 401 it was answered with, and nothing else. */
    private fun assertUnauthorized(block: suspend () -> Unit) = runBlocking {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a 401, got $error", error is ApiException && error.status == 401)
    }

    @Test
    fun `on 401 refreshes the token once and retries with the new bearer`() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(tokens())
        server.enqueue(user())

        assertEquals(42L, api.whoami().id)

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals("application/x-www-form-urlencoded", refresh.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("grant_type=refresh_token&client_id=cid&refresh_token=refresh-1", refresh.body.readUtf8())
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals(AuthTokens("new", "refresh-2", 87_400), store.get())
    }

    @Test
    fun `a refresh token Shikimori rejects ends the session and the 401 stands`() {
        for (status in listOf(400, 401)) {
            runBlocking { store.set(AuthTokens("old", "refresh-1", 0)) }
            server.enqueue(unauthorized())
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"invalid_grant","error_description":"expired"}"""))

            assertUnauthorized { api.whoami() }

            assertNull(runBlocking { store.get() })
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a proxy that rate limits the refresh leaves the session alone`() =
        assertSessionSurvives(MockResponse().setResponseCode(429).setBody("too many requests"))

    @Test
    fun `a proxy that cannot reach Shikimori leaves the session alone`() =
        assertSessionSurvives(MockResponse().setResponseCode(502).setBody("upstream unavailable"))

    @Test
    fun `a proxy that refuses the request itself leaves the session alone`() =
        // The worker's own refusals are 400s in plain text: a wrong client id, a body it would not
        // take, a deploy that has no secret yet. None of them says anything about this session.
        assertSessionSurvives(MockResponse().setResponseCode(400).setBody("unknown client"))

    @Test
    fun `an OAuth error other than invalid_grant leaves the session alone`() =
        // `invalid_client` is the worker's secret being wrong. Signing the viewer out would not
        // fix it, and the next sign-in would fail the same way.
        assertSessionSurvives(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client"}"""))

    @Test
    fun `a bodyless rejection leaves the session alone`() = assertSessionSurvives(unauthorized())

    /**
     * A refresh that fails this way must cost the request and nothing else. A session that is
     * cleared cannot be retried — the viewer has to sign in again — so anything short of «this
     * refresh token is no good» leaves the credentials where they are.
     */
    private fun assertSessionSurvives(refreshAnswer: MockResponse) {
        server.enqueue(unauthorized())
        server.enqueue(refreshAnswer)

        assertUnauthorized { api.whoami() }

        assertEquals(AuthTokens("old", "refresh-1", 0), runBlocking { store.get() })
        assertEquals("the refresh must have been attempted", 2, server.requestCount)
    }

    @Test
    fun `an unreachable proxy leaves the session alone`() {
        val dead = MockWebServer()
        dead.start()
        val nowhere = dead.url("/").toString()
        dead.shutdown()
        val api = SessionShikimoriApi(serverClient(server, proxy = nowhere), store, clock)
        server.enqueue(unauthorized())

        assertUnauthorized { api.whoami() }

        assertEquals(AuthTokens("old", "refresh-1", 0), runBlocking { store.get() })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a build with no proxy address leaves the session alone`() {
        // Nothing the viewer can do fixes a missing AUTH_PROXY_URL, and signing them out does not
        // help: the next build with an address must find the session still there.
        val api = SessionShikimoriApi(serverClient(server, proxy = ""), store, clock)
        server.enqueue(unauthorized())

        assertUnauthorized { api.whoami() }

        assertEquals(AuthTokens("old", "refresh-1", 0), runBlocking { store.get() })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a retry rejected with 401 does not refresh a second time`() {
        server.enqueue(unauthorized())
        server.enqueue(tokens())
        server.enqueue(unauthorized())

        assertUnauthorized { api.whoami() }

        assertEquals(3, server.requestCount)
    }

    @Test
    fun `missing tokens leave requests anonymous without a refresh`() {
        runBlocking { store.set(null) }
        server.enqueue(unauthorized())

        assertUnauthorized { api.whoami() }

        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a token another request already rotated is reused without another refresh`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer old") return user()
                // Another request finished its refresh while this one was in flight.
                runBlocking { store.set(AuthTokens("rotated", "refresh-2", 87_400)) }
                return unauthorized()
            }
        }

        assertEquals(42L, api.whoami().id)

        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer rotated", retry.getHeader("Authorization"))
        assertFalse(retry.path.orEmpty().startsWith("/oauth/"))
    }

    @Test
    fun `an explicit candidate identity is sent as given and never refreshed`() {
        server.enqueue(user())
        runBlocking { api.whoami(accessToken = "candidate") }
        assertEquals("Bearer candidate", server.takeRequest().getHeader("Authorization"))

        server.enqueue(unauthorized())
        assertUnauthorized { api.whoami(accessToken = "candidate") }
        assertEquals(2, server.requestCount)
        assertEquals("old", runBlocking { store.get() }?.accessToken)
    }

    @Test
    fun `a refresh keeps the verified account binding`() = runBlocking {
        store.set(AuthTokens("old", "refresh-1", 0, 42))
        server.enqueue(unauthorized())
        server.enqueue(tokens())
        server.enqueue(user())

        api.whoami()

        assertEquals(AuthTokens("new", "refresh-2", 87_400, 42), store.get())
    }

    @Test
    fun `a successful in-flight refresh cannot undo a logout`() = assertRefreshCannotOverwrite(null, succeeds = true)

    @Test
    fun `a failed in-flight refresh leaves a logout intact`() = assertRefreshCannotOverwrite(null, succeeds = false)

    @Test
    fun `a successful in-flight refresh cannot replace a newer login`() =
        assertRefreshCannotOverwrite(AuthTokens("login", "login-refresh", 99_000), succeeds = true)

    @Test
    fun `a failed in-flight refresh cannot clear a newer login`() =
        assertRefreshCannotOverwrite(AuthTokens("login", "login-refresh", 99_000), succeeds = false)

    @Test
    fun `a new session with identical credentials still invalidates an in-flight refresh`() =
        assertRefreshCannotOverwrite(AuthTokens("old", "refresh-1", 0), succeeds = true)

    private fun assertRefreshCannotOverwrite(replacement: AuthTokens?, succeeds: Boolean) {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/oauth/token") return unauthorized()
                refreshStarted.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                return if (succeeds) {
                    MockResponse().setBody("""{"access_token":"stale-refresh","refresh_token":"stale-refresh-token","expires_in":86400}""")
                } else {
                    MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                }
            }
        }
        runBlocking {
            val outcome = async(Dispatchers.IO) { runCatching { api.whoami() }.exceptionOrNull() }
            try {
                assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
                store.set(replacement)
                releaseRefresh.countDown()
                val error = outcome.await()
                assertTrue("expected the 401 to stand, got $error", error is ApiException && error.status == 401)
                assertEquals(replacement, store.get())
                assertEquals("stale request must not retry into another session", 2, server.requestCount)
            } finally {
                releaseRefresh.countDown()
            }
        }
    }
}
