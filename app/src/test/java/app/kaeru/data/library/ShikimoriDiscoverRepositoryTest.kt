package app.kaeru.data.library

import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.error.HttpError
import app.kaeru.test.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

private const val FRIEREN = 52991
private const val MISSING = "/assets/globals/missing_original.jpg"
private const val REAL_POSTER = "https://shikimori.io/uploads/poster/animes/52991/main.webp"

class ShikimoriDiscoverRepositoryTest {
    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-09-13T20:00:00Z"))
    private lateinit var api: ShikimoriApi
    private lateinit var repo: ShikimoriDiscoverRepository

    private val summer = Season(SeasonKind.SUMMER, 2026)
    private val fall = Season(SeasonKind.FALL, 2026)

    @Before
    fun setUp() {
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ShikimoriApi::class.java)
        repo = ShikimoriDiscoverRepository(api, PosterEnricher(api), Dispatchers.Unconfined, clock)
    }

    @After
    fun tearDown() = server.shutdown()

    /** One catalogue answer plus the GraphQL poster lookup that always follows it. */
    private fun enqueueTitles(poster: String = MISSING, realPoster: String? = REAL_POSTER) {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":$FRIEREN,"name":"Sousou no Frieren","russian":"Фрирен",""" +
                    """"image":{"original":"$poster"},"score":"9.1","status":"ongoing",""" +
                    """"episodes":28,"episodes_aired":24,"aired_on":"2023-09-29"}]""",
            ),
        )
        val animes = realPoster?.let { """{"id":"$FRIEREN","poster":{"mainUrl":"$it"}}""" }.orEmpty()
        server.enqueue(MockResponse().setBody("""{"data":{"animes":[$animes]}}"""))
    }

    // --- what goes on the wire -----------------------------------------------------------------

    @Test
    fun `popular now asks for ongoing titles ordered by popularity`() = runTest {
        enqueueTitles()

        val titles = repo.popularNow().getOrThrow()

        assertEquals(listOf(FRIEREN), titles.map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/animes", url.encodedPath)
        assertEquals("ongoing", url.queryParameter("status"))
        assertEquals("popularity", url.queryParameter("order"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("true", url.queryParameter("censored"))
        assertNull(url.queryParameter("season"))
    }

    @Test
    fun `a season is asked for by the name shikimori gives it`() = runTest {
        enqueueTitles()

        repo.seasonal(summer).getOrThrow()

        val url = server.takeRequest().requestUrl!!
        assertEquals("summer_2026", url.queryParameter("season"))
        assertEquals("popularity", url.queryParameter("order"))
        // A season is what aired then, finished or not, so the status filter stays off.
        assertNull(url.queryParameter("status"))
    }

    @Test
    fun `a poster the REST field hides is replaced from graphql`() = runTest {
        enqueueTitles()

        val titles = repo.popularNow().getOrThrow()

        assertEquals(REAL_POSTER, titles.single().posterUrl)
        assertEquals("/api/animes", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/graphql", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a poster graphql cannot improve is left as REST returned it`() = runTest {
        enqueueTitles(poster = "/system/animes/original/52991.jpg", realPoster = null)

        val titles = repo.popularNow().getOrThrow()

        assertEquals("https://shikimori.io/system/animes/original/52991.jpg", titles.single().posterUrl)
    }

    // --- the cache -----------------------------------------------------------------------------

    @Test
    fun `a second read inside six hours never reaches the network`() = runTest {
        enqueueTitles()

        val first = repo.popularNow().getOrThrow()
        clock.advance(Duration.ofHours(5).plusMinutes(59))
        val second = repo.popularNow().getOrThrow()

        assertEquals(first, second)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `six hours on, the row is read again`() = runTest {
        enqueueTitles()
        enqueueTitles()

        repo.popularNow().getOrThrow()
        clock.advance(Duration.ofHours(6))
        repo.popularNow().getOrThrow()

        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a forced read ignores a cache that is still fresh`() = runTest {
        enqueueTitles()
        enqueueTitles()

        repo.popularNow().getOrThrow()
        repo.popularNow(force = true).getOrThrow()

        assertEquals(4, server.requestCount)
    }

    @Test
    fun `each season is remembered under its own name`() = runTest {
        enqueueTitles()
        enqueueTitles()

        repo.seasonal(summer).getOrThrow()
        repo.seasonal(fall).getOrThrow()
        repo.seasonal(summer).getOrThrow()

        assertEquals(4, server.requestCount)
        assertEquals("summer_2026", server.takeRequest().requestUrl!!.queryParameter("season"))
        server.takeRequest()
        assertEquals("fall_2026", server.takeRequest().requestUrl!!.queryParameter("season"))
    }

    @Test
    fun `the seasonal cache and the ongoing one do not share a key`() = runTest {
        enqueueTitles()
        enqueueTitles()

        repo.popularNow().getOrThrow()
        repo.seasonal(summer).getOrThrow()

        assertEquals(4, server.requestCount)
    }

    // --- failure -------------------------------------------------------------------------------

    @Test
    fun `a refused request comes back as a domain failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repo.popularNow()

        assertTrue(result.exceptionOrNull() is HttpError)
        assertEquals(503, (result.exceptionOrNull() as HttpError).code)
    }

    @Test
    fun `a failure is not cached, so the next read tries again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        enqueueTitles()

        assertTrue(repo.popularNow().isFailure)
        val titles = repo.popularNow().getOrThrow()

        assertEquals(listOf(FRIEREN), titles.map { it.id })
    }
}
