package app.kaeru.data.kodik

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

class KodikTokenProviderTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = MutableClock(Instant.parse("2026-09-12T20:00:00Z"))
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        server.start()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            folder.root.resolve("prefs.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun provider(configuredToken: String = "") = DefaultKodikTokenProvider(
        client = OkHttpClient(),
        dataStore = dataStore,
        configuredToken = configuredToken,
        clock = clock,
        addPlayersUrl = server.url("/add-players.min.js?v=2").toString(),
    )

    private fun script(token: String) =
        MockResponse().setBody("""!function(){var r={};r.token="$token";r.other="noise";}();""")

    @Test
    fun `a token stored by settings wins over BuildConfig and the script`() = runTest {
        dataStore.edit { it[stringPreferencesKey("kodik_token_override")] = "from-settings" }
        server.enqueue(script("fromthescript0000000000000000000"))

        assertEquals("from-settings", provider(configuredToken = "from-buildconfig").token())

        assertEquals("settings token must not trigger a script fetch", 0, server.requestCount)
    }

    @Test
    fun `the BuildConfig token is used when settings hold none`() = runTest {
        server.enqueue(script("fromthescript0000000000000000000"))

        assertEquals("from-buildconfig", provider(configuredToken = "from-buildconfig").token())

        assertEquals("BuildConfig token must not trigger a script fetch", 0, server.requestCount)
    }

    @Test
    fun `a blank BuildConfig token falls through to the script`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))

        assertEquals("0000000000000000000000000000abcd", provider(configuredToken = "  ").token())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the script token is fetched once and reused within 24 hours`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))
        val subject = provider()

        assertEquals("0000000000000000000000000000abcd", subject.token())
        clock.advance(Duration.ofHours(23).plusMinutes(59))
        assertEquals("0000000000000000000000000000abcd", subject.token())

        assertEquals("the cached token must be reused", 1, server.requestCount)
    }

    @Test
    fun `the cached script token survives a new provider instance`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))

        assertEquals("0000000000000000000000000000abcd", provider().token())
        assertEquals("0000000000000000000000000000abcd", provider().token())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the cached script token is refetched once it is 24 hours old`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))
        server.enqueue(script("1111111111111111111111111111beef"))
        val subject = provider()

        assertEquals("0000000000000000000000000000abcd", subject.token())
        clock.advance(Duration.ofHours(24).plusSeconds(1))

        assertEquals("1111111111111111111111111111beef", subject.token())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `forceRefresh bypasses a cache that is still fresh`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))
        server.enqueue(script("1111111111111111111111111111beef"))
        val subject = provider()

        assertEquals("0000000000000000000000000000abcd", subject.token())
        assertEquals("1111111111111111111111111111beef", subject.token(forceRefresh = true))
        assertEquals("1111111111111111111111111111beef", subject.token())

        assertEquals("forceRefresh refetches, then repopulates the cache", 2, server.requestCount)
    }

    @Test
    fun `the script request carries a browser user agent and a player referer`() = runTest {
        server.enqueue(script("0000000000000000000000000000abcd"))

        provider().token()

        val request = server.takeRequest()
        assertEquals(KodikConstants.BROWSER_UA, request.getHeader("User-Agent"))
        assertEquals("${KodikConstants.PLAYER_HOST}/", request.getHeader("Referer"))
    }

    @Test
    fun `a script without a token fails with NoToken`() = runTest {
        server.enqueue(MockResponse().setBody("var x = 1;"))

        val error = runCatching { provider().token() }.exceptionOrNull()

        assertTrue("expected NoToken, got $error", error is KodikError.NoToken)
    }

    @Test
    fun `a non-2xx script response fails with NoToken, not as a connectivity failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("nope"))

        val error = runCatching { provider().token() }.exceptionOrNull()

        assertTrue("expected NoToken, got $error", error is KodikError.NoToken)
    }

    @Test
    fun `an unreachable script host fails with Network`() = runTest {
        server.shutdown()

        val error = runCatching { provider().token() }.exceptionOrNull()

        assertTrue("expected Network, got $error", error is KodikError.Network)
    }
}
