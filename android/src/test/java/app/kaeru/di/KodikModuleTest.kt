package app.kaeru.di

import app.kaeru.data.kodik.KodikConstants
import app.kaeru.data.shikimori.UserAgentInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KodikModuleTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the player client overrides the app user agent with a browser one`() = runTest {
        val plain = OkHttpClient.Builder().addInterceptor(UserAgentInterceptor("Kaeru/0.1.0")).build()
        val client = KodikModule.playerClient(plain)
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/serial/1/h/720p")).build()).execute().close()

        assertEquals(KodikConstants.BROWSER_UA, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `the api points at the kodik api host and the pages at the player host`() {
        assertEquals("https://kodik-api.com/", KodikConstants.API_URL)
        assertEquals(KodikConstants.PLAYER_HOST, KodikModule.playerHost())
        assertEquals(KodikConstants.ADD_PLAYERS_URL, KodikModule.addPlayersUrl())
    }
}
