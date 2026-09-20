package app.kaeru.data.shikimori

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ShikimoriApiTest {
    private val server = MockWebServer()
    private lateinit var api: ShikimoriApi

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("shikimori/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ShikimoriApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `parses anime list with unknown fields ignored`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("animes_list.json")))

        val list = api.animesByIds(ids = "52991,60000", limit = 50)

        assertEquals(2, list.size)
        assertEquals("Провожающая в последний путь Фрирен", list[0].russian)
        assertEquals(28, list[0].episodes)
        assertEquals("ongoing", list[1].status)
        assertEquals("/api/animes?ids=52991%2C60000&limit=50", server.takeRequest().path)
    }

    @Test
    fun `parses details with studios screenshots and next episode`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("anime_details.json")))

        val details = api.anime(60000)

        assertEquals("MAPPA", details.studios.first().name)
        assertEquals(2, details.screenshots.size)
        assertEquals("2026-09-14T17:00:00.000+03:00", details.nextEpisodeAt)
        assertEquals("/api/animes/60000", server.takeRequest().path)
    }

    @Test
    fun `parses user rates and sends every filter`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("user_rates.json")))

        val rates = api.userRates(userId = 42, status = "watching", page = 1, limit = 1000)

        assertEquals(2, rates.size)
        assertEquals(52991, rates[0].targetId)
        assertEquals(20, rates[0].episodes)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Anime", url.queryParameter("target_type"))
        assertEquals("42", url.queryParameter("user_id"))
        assertEquals("watching", url.queryParameter("status"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("1000", url.queryParameter("limit"))
    }

    @Test
    fun `update user rate sends wrapped payload`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("user_rates.json").removePrefix("[").substringBefore("},") + "}"))

        api.updateUserRate(111, UserRateRequest(UserRatePayload(episodes = 21)))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v2/user_rates/111", request.path)
        assertEquals("""{"user_rate":{"episodes":21}}""", request.body.readUtf8())
    }

    @Test
    fun `whoami uses users endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":42,"nickname":"frog","avatar":null,"ignored":true}"""))

        val user = api.whoami()

        assertEquals(42L, user.id)
        assertEquals("frog", user.nickname)
        assertEquals("/api/users/whoami", server.takeRequest().path)
    }

    @Test
    fun `screenshots and search use their documented routes`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"original":"/one.jpg","preview":"/one-preview.jpg"}]"""))
        server.enqueue(MockResponse().setBody(fixture("animes_list.json")))

        val screenshots = api.screenshots(60000)
        val searchResults = api.search("frog show", limit = 12)

        assertEquals("/one.jpg", screenshots.single().original)
        assertEquals(2, searchResults.size)
        assertEquals("/api/animes/60000/screenshots", server.takeRequest().path)
        assertEquals("/api/animes?search=frog%20show&limit=12", server.takeRequest().path)
    }

    @Test
    fun `create user rate posts wrapped payload`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("user_rates.json").removePrefix("[").substringBefore("},") + "}"))

        api.createUserRate(
            UserRateRequest(
                UserRatePayload(
                    userId = 42,
                    targetId = 52991,
                    targetType = "Anime",
                    status = "watching",
                    episodes = 1,
                ),
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/user_rates", request.path)
        assertEquals(
            """{"user_rate":{"user_id":42,"target_id":52991,"target_type":"Anime","status":"watching","episodes":1}}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `user agent interceptor replaces an existing header`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Legacy/1.0")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(UserAgentInterceptor("Kaeru/0.1.0"))
            .build()
        val userAgentApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ShikimoriApi::class.java)
        server.enqueue(MockResponse().setBody("""{"id":42,"nickname":"frog"}"""))

        userAgentApi.whoami()

        assertEquals("Kaeru/0.1.0", server.takeRequest().getHeader("User-Agent"))
    }
}
