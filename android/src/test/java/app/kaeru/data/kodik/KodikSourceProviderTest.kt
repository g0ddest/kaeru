package app.kaeru.data.kodik

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.shared.data.kodik.KodikClient
import app.kaeru.shared.data.network.HttpTransport
import app.kaeru.test.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The Android edge of the shared Kodik chain: what the source's answers and failures become on
 * this side of `domain`. The chain itself — the catalogue, its cache, the lists per track, the
 * token — is the shared module's, and is tested there (`KodikClientTest`, `KodikNetworkTest`).
 */
class KodikSourceProviderTest {
    private val clock = MutableClock(Instant.parse("2026-09-12T20:00:00Z"))
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private var script = "var token=\"token1\";"
    private var getPlayer: Pair<String, HttpStatusCode> = FOUND to HttpStatusCode.OK
    private var playerPage: Pair<String, HttpStatusCode> = fixture("player.html") to HttpStatusCode.OK
    private var unreachable = false

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("kodik/$name")!!.bufferedReader().readText()

    private val provider by lazy {
        val http = HttpTransport(HttpClient(MockEngine { request ->
            requests += request
            if (unreachable) throw Exception("connection refused")
            when {
                request.url.host == "kodik-add.com" -> respond(script)
                request.url.host == "kodik-api.com" -> respond(getPlayer.first, getPlayer.second, jsonHeaders)
                request.url.encodedPath == "/ftor" -> respond(fixture("links.json"), headers = jsonHeaders)
                else -> respond(playerPage.first, playerPage.second)
            }
        }))
        KodikSourceProvider(KodikClient(http, nowMillis = { clock.millis() }), clock)
    }

    @Test
    fun `translations become the app's tracks, kinds and counts included`() = runTest {
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
    fun `resolve returns every known quality, the best of them and when it resolved`() = runTest {
        val stream = provider.resolve(SHIKIMORI_ID, episode = 1).getOrThrow()

        assertEquals(SHIKIMORI_ID, stream.animeId)
        assertEquals(1, stream.episode)
        assertEquals(3560, stream.translation.id)
        assertEquals(setOf(Quality.P360, Quality.P480, Quality.P720), stream.urls.keys)
        assertEquals(Quality.P720, stream.best)
        assertEquals(clock.instant(), stream.resolvedAt)
        stream.urls.values.forEach { assertTrue(it.startsWith("https://")) }
    }

    @Test
    fun `resolve opens the season the caller remembered and hands it back on the track`() = runTest {
        val remembered = provider.translations(SHIKIMORI_ID).getOrThrow().first().copy(season = 2)

        val stream = provider.resolve(SHIKIMORI_ID, episode = 1, translation = remembered).getOrThrow()

        assertEquals(2, stream.translation.season)
        assertEquals("2", requests.last { it.url.encodedPath.startsWith("/serial/") }.url.parameters["season"])
    }

    @Test
    fun `a film with no chooser plays as episode one whatever episode was asked for`() = runTest {
        playerPage = fixture("movie-single-track.html") to HttpStatusCode.OK

        val stream = provider.resolve(SHIKIMORI_ID, episode = 5).getOrThrow()

        assertEquals(1, stream.episode)
        assertEquals(923, stream.translation.id)
        assertEquals("AnimeVost", stream.translation.title)
    }

    @Test
    fun `what a track's page listed, and forgetting it, reach the shared client`() = runTest {
        assertNull(provider.listedEpisodes(SHIKIMORI_ID, 3560))
        provider.resolve(SHIKIMORI_ID, episode = 1).getOrThrow()
        assertEquals((1..28).toSet(), provider.listedEpisodes(SHIKIMORI_ID, 3560))

        provider.forget(SHIKIMORI_ID)

        assertNull(provider.listedEpisodes(SHIKIMORI_ID, 3560))
    }

    // --- failures, as the ui will read them -----------------------------------------------------

    @Test
    fun `a title kodik has no player for is missing as a title`() = runTest {
        getPlayer = """{"found":false}""" to HttpStatusCode.OK

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
        assertEquals(SHIKIMORI_ID, (error as EpisodeNotAvailable).animeId)
        assertNull(error.episode)
        assertEquals(EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE, error.reason)
    }

    @Test
    fun `an episode the track does not list, or a track kodik does not offer, is missing in the translation`() = runTest {
        val missing = provider.resolve(SHIKIMORI_ID, episode = 99).exceptionOrNull()
        assertTrue("expected EpisodeNotAvailable, got $missing", missing is EpisodeNotAvailable)
        assertEquals(99, (missing as EpisodeNotAvailable).episode)
        assertEquals(EpisodeUnavailableReason.NOT_IN_TRANSLATION, missing.reason)

        val unknown = Translation(id = -1, title = "Nope", type = TranslationKind.VOICE, episodesCount = 12)
        val notOffered = provider.resolve(SHIKIMORI_ID, episode = 1, translation = unknown).exceptionOrNull()
        assertEquals(EpisodeUnavailableReason.NOT_IN_TRANSLATION, (notOffered as EpisodeNotAvailable).reason)
    }

    @Test
    fun `a player page kodik changed fails with SourceFormatChanged`() = runTest {
        playerPage = "<html><body>redesigned</body></html>" to HttpStatusCode.OK

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceFormatChanged, got $error", error is SourceFormatChanged)
        assertTrue((error as SourceFormatChanged).step.isNotBlank())
    }

    @Test
    fun `a token that cannot be obtained blames the key, not the source`() = runTest {
        script = "var x = 1;"

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
        assertEquals(SourceUnavailableReason.NO_KEY, (error as SourceUnavailable).reason)
    }

    @Test
    fun `a kodik host that answers with an error turns us away, and is never shikimori being down`() = runTest {
        getPlayer = """{"error":"oops"}""" to HttpStatusCode.ServiceUnavailable

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
        assertEquals(SourceUnavailableReason.REJECTED, (error as SourceUnavailable).reason)
    }

    @Test
    fun `a player host that turns us away asks to retry, not to check the internet`() = runTest {
        playerPage = "denied" to HttpStatusCode.Forbidden

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
        assertEquals(SourceUnavailableReason.REJECTED, (error as SourceUnavailable).reason)
    }

    @Test
    fun `an unexpected failure is never handed to the ui raw`() = runTest {
        // A shikimori id the shared client refuses outright: an IllegalArgumentException, not ours.
        val error = provider.translations(0).exceptionOrNull()

        assertTrue("expected SourceUnavailable, got $error", error is SourceUnavailable)
        assertEquals(SourceUnavailableReason.REJECTED, (error as SourceUnavailable).reason)
    }

    @Test
    fun `an unreachable kodik fails with NetworkUnavailable`() = runTest {
        unreachable = true

        val error = provider.translations(SHIKIMORI_ID).exceptionOrNull()

        assertTrue("expected NetworkUnavailable, got $error", error is NetworkUnavailable)
    }

    private companion object {
        const val SHIKIMORI_ID = 52991
        const val FOUND =
            """{"found":true,"allowed":1,"quality":"720p","translation":"AniLibria.TV",""" +
                """"link":"//kodikplayer.com/serial/53973/cf62e729fdb71a0b7fb148ba6fc48ad6/720p"}"""
    }
}
