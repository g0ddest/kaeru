package app.kaeru.data.kodik

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.test.MutableClock
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import java.time.Instant

class KodikSourceProviderTest {
    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-09-12T20:00:00Z"))
    private val tokens = FakeTokenProvider()
    private lateinit var routes: KodikRoutes
    private lateinit var provider: KodikSourceProvider

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("kodik/$name")!!.bufferedReader().readText()

    private fun parsedPage() = KodikHtmlParser.parse(fixture("player.html"))

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Before
    fun setUp() {
        server.start()
        routes = KodikRoutes(fixture("player.html"), fixture("links.json"))
        server.dispatcher = routes
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(kodikJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KodikApi::class.java)
        provider = KodikSourceProvider(
            api = api,
            tokenProvider = tokens,
            extractor = KodikLinkExtractor(
                client = OkHttpClient(),
                playerHost = server.url("/").toString().removeSuffix("/"),
            ),
            clock = clock,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `translations lists every track from the player page`() = runTest {
        val translations = provider.translations(SHIKIMORI_ID).getOrThrow()

        assertEquals(33, translations.size)
        val first = translations.first()
        assertEquals(3560, first.id)
        assertTrue(first.title.startsWith("#студияБУБНЯЖА"))
        assertEquals(TranslationKind.VOICE, first.type)
        assertEquals(28, first.episodesCount)
        assertEquals(1, first.season)
        assertTrue(translations.any { it.type == TranslationKind.SUBTITLES })
    }

    @Test
    fun `get-player is posted with the shikimori id the token and the anime types`() = runTest {
        provider.translations(SHIKIMORI_ID).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/get-player", request.path)
        val body = request.body.readUtf8()
        assertTrue("expected the token in $body", body.contains("token=token-1"))
        assertTrue("expected the shikimori id in $body", body.contains("shikimoriID=$SHIKIMORI_ID"))
        assertTrue("expected the anime types in $body", body.contains("types=anime%2Canime-serial"))
    }

    @Test
    fun `translations fails with EpisodeNotAvailable when kodik has no player`() = runTest {
        routes.getPlayerResponses += jsonResponse("""{"found":false}""")

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
        assertEquals(SHIKIMORI_ID, (error as EpisodeNotAvailable).animeId)
        assertNull(error.episode)
    }

    @Test
    fun `translations fails with EpisodeNotAvailable when the answer carries no link`() = runTest {
        routes.getPlayerResponses += jsonResponse("""{"found":true}""")

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
    }

    @Test
    fun `translations are cached for six hours`() = runTest {
        provider.translations(SHIKIMORI_ID).getOrThrow()
        clock.advance(Duration.ofHours(5).plusMinutes(59))
        provider.translations(SHIKIMORI_ID).getOrThrow()

        assertEquals(1, routes.getPlayerCalls)
    }

    @Test
    fun `the translation cache expires after six hours`() = runTest {
        provider.translations(SHIKIMORI_ID).getOrThrow()
        clock.advance(Duration.ofHours(6).plusSeconds(1))
        provider.translations(SHIKIMORI_ID).getOrThrow()

        assertEquals(2, routes.getPlayerCalls)
    }

    @Test
    fun `the translation cache is kept per anime`() = runTest {
        provider.translations(SHIKIMORI_ID).getOrThrow()
        provider.translations(SHIKIMORI_ID + 1).getOrThrow()

        assertEquals(2, routes.getPlayerCalls)
    }

    @Test
    fun `a failure is never cached so the next call tries again`() = runTest {
        routes.getPlayerResponses += jsonResponse("""{"found":false}""")
        provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertEquals(33, provider.translations(SHIKIMORI_ID).getOrThrow().size)
        assertEquals(2, routes.getPlayerCalls)
    }

    @Test
    fun `resolve reuses the player page the translation listing already fetched`() = runTest {
        provider.translations(SHIKIMORI_ID).getOrThrow()
        provider.resolve(SHIKIMORI_ID, episode = 1).getOrThrow()

        assertEquals(1, routes.getPlayerCalls)
    }

    @Test
    fun `a token kodik rejects with 401 is refreshed and the call retried once`() = runTest {
        routes.getPlayerResponses += jsonResponse(TOKEN_REJECTED, code = 401)

        val translations = provider.translations(SHIKIMORI_ID).getOrThrow()

        assertEquals(33, translations.size)
        assertEquals(2, routes.getPlayerCalls)
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf("token-1", "token-2"), routes.tokensSeen)
    }

    @Test
    fun `a token rejection reported inside a 200 body is refreshed and retried once`() = runTest {
        routes.getPlayerResponses += jsonResponse(TOKEN_REJECTED)

        val translations = provider.translations(SHIKIMORI_ID).getOrThrow()

        assertEquals(33, translations.size)
        assertEquals(2, routes.getPlayerCalls)
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun `a token rejected twice fails instead of retrying forever`() = runTest {
        routes.getPlayerResponses += jsonResponse(TOKEN_REJECTED, code = 401)
        routes.getPlayerResponses += jsonResponse(TOKEN_REJECTED, code = 401)

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected a failure", error != null)
        assertEquals(2, routes.getPlayerCalls)
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun `resolve opens the media page of the chosen translation for that episode`() = runTest {
        val chosen = provider.translations(SHIKIMORI_ID).getOrThrow()[1]
        val option = parsedPage().translations.first { it.id == chosen.id }
        server.takeRequest()
        server.takeRequest()

        provider.resolve(SHIKIMORI_ID, episode = 4, translation = chosen).getOrThrow()

        val url = server.takeRequest().requestUrl!!
        assertEquals("/serial/${option.mediaId}/${option.mediaHash}/720p", url.encodedPath)
        assertEquals("1", url.queryParameter("season"))
        assertEquals("4", url.queryParameter("episode"))
    }

    @Test
    fun `resolve opens the season the caller remembered`() = runTest {
        val remembered = provider.translations(SHIKIMORI_ID).getOrThrow().first().copy(season = 2)
        server.takeRequest()
        server.takeRequest()

        val stream = provider.resolve(SHIKIMORI_ID, episode = 1, translation = remembered).getOrThrow()

        assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("season"))
        assertEquals(2, stream.translation.season)
    }

    @Test
    fun `resolve posts the id and hash of the requested episode`() = runTest {
        val episode = parsedPage().episodes.first { it.number == 7 }

        provider.resolve(SHIKIMORI_ID, episode = 7).getOrThrow()

        val body = routes.lastFtorBody!!
        assertTrue("expected the episode id in $body", body.contains("&id=${episode.mediaId}"))
        assertTrue("expected the episode hash in $body", body.contains("&hash=${episode.mediaHash}"))
        assertTrue("expected type=seria in $body", body.contains("&type=seria"))
    }

    @Test
    fun `resolve returns every decoded quality and remembers when it resolved`() = runTest {
        val stream = provider.resolve(SHIKIMORI_ID, episode = 1).getOrThrow()

        assertEquals(SHIKIMORI_ID, stream.animeId)
        assertEquals(1, stream.episode)
        assertEquals(setOf(Quality.P360, Quality.P480, Quality.P720), stream.urls.keys)
        assertEquals(Quality.P720, stream.best)
        assertEquals(clock.instant(), stream.resolvedAt)
        stream.urls.values.forEach { assertTrue(it.startsWith("https://")) }
    }

    @Test
    fun `resolve without a translation takes the first one kodik lists`() = runTest {
        val stream = provider.resolve(SHIKIMORI_ID, episode = 1).getOrThrow()

        assertEquals(3560, stream.translation.id)
    }

    @Test
    fun `resolve fails with EpisodeNotAvailable for an episode the page does not list`() = runTest {
        val error = provider.resolve(SHIKIMORI_ID, episode = 99).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
        assertEquals(SHIKIMORI_ID, (error as EpisodeNotAvailable).animeId)
        assertEquals(99, error.episode)
    }

    @Test
    fun `resolve fails with EpisodeNotAvailable when kodik does not offer the translation`() = runTest {
        val unknown = Translation(id = -1, title = "Nope", type = TranslationKind.VOICE, episodesCount = 12)

        val error = provider.resolve(SHIKIMORI_ID, episode = 1, translation = unknown).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
    }

    @Test
    fun `a player page kodik changed fails with SourceFormatChanged`() = runTest {
        routes.playerPageBody = "<html><body>redesigned</body></html>"

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceFormatChanged, got $error", error is SourceFormatChanged)
        assertTrue((error as SourceFormatChanged).step.isNotBlank())
    }

    @Test
    fun `a token that cannot be obtained fails with SourceUnavailable`() = runTest {
        tokens.failure = KodikError.NoToken()

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
    }

    @Test
    fun `a kodik api server error fails with SourceUnavailable, never as shikimori being down`() = runTest {
        routes.getPlayerResponses += jsonResponse("""{"error":"oops"}""", code = 503)

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
        assertEquals(1, routes.getPlayerCalls)
    }

    @Test
    fun `an unreachable kodik fails with NetworkUnavailable`() = runTest {
        server.shutdown()

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected NetworkUnavailable, got $error", error is NetworkUnavailable)
    }

    private class FakeTokenProvider : KodikTokenProvider {
        var refreshes = 0
        var failure: Throwable? = null
        private var current = "token-1"

        override suspend fun token(forceRefresh: Boolean): String {
            failure?.let { throw it }
            if (forceRefresh) {
                refreshes++
                current = "token-2"
            }
            return current
        }
    }

    /** Routes by path so a test does not have to predict the exact request order. */
    private class KodikRoutes(
        private val playerPage: String,
        private val links: String,
    ) : Dispatcher() {
        var getPlayerCalls = 0
        var playerPageBody: String? = null
        var lastFtorBody: String? = null

        /** Consumed in order; once empty every call answers with a found player. */
        val getPlayerResponses = ArrayDeque<MockResponse>()
        val tokensSeen = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/get-player") -> getPlayer(request)
                path.startsWith("/ftor") -> {
                    // copy(): the recorded request keeps its body for takeRequest() assertions.
                    lastFtorBody = request.body.copy().readUtf8()
                    json(links)
                }
                else -> MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(playerPageBody ?: playerPage)
            }
        }

        private fun getPlayer(request: RecordedRequest): MockResponse {
            getPlayerCalls++
            tokensSeen += request.body.copy().readUtf8()
                .split('&')
                .first { it.startsWith("token=") }
                .removePrefix("token=")
            return getPlayerResponses.removeFirstOrNull() ?: json(FOUND)
        }

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

        private companion object {
            const val FOUND =
                """{"found":true,"allowed":1,"quality":"720p","translation":"AniLibria.TV",""" +
                    """"link":"//kodikplayer.com/serial/53973/cf62e729fdb71a0b7fb148ba6fc48ad6/720p"}"""
        }
    }

    private companion object {
        const val SHIKIMORI_ID = 52991
        const val TOKEN_REJECTED = """{"error":"Отсутствует или неверный токен"}"""
    }
}
