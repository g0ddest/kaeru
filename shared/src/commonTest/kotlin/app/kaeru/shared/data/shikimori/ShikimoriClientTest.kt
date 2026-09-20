package app.kaeru.shared.data.shikimori

import app.kaeru.shared.ApiException
import app.kaeru.shared.TestFixtures
import app.kaeru.shared.data.network.HttpTransport
import app.kaeru.shared.data.network.NetworkException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * The endpoint client as Android drives it: the routes, the parameters, the payloads, the token
 * on each call. What Swift gets out of the same client is covered through `NativeApi`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShikimoriClientTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private fun fixture(name: String) = TestFixtures.text("shikimori/$name")
    private fun HttpRequestData.text() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun TestScope.client(
        proxy: String = "https://proxy.example",
        userAgent: String = "Kaeru/9.9",
        handler: MockRequestHandler,
    ) = ShikimoriClient(
        HttpTransport(HttpClient(MockEngine { request -> requests += request; handler(request) })),
        "cid", proxy, ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime }), userAgent,
    )

    @Test fun listsByIdsParseWithUnknownFieldsIgnoredAndGoFiftyAtATime() = runTest {
        val client = client { respond(fixture("animes_list.json"), headers = headers) }
        val list = client.animesByIds(listOf(52991, 60000))
        assertEquals(2, list.size)
        assertEquals("Провожающая в последний путь Фрирен", list[0].russian)
        assertEquals(28, list[0].episodes)
        assertEquals("ongoing", list[1].status)
        assertEquals("/api/animes", requests.single().url.encodedPath)
        assertEquals("52991,60000", requests.single().url.parameters["ids"])
        assertEquals("50", requests.single().url.parameters["limit"])
        assertEquals("Kaeru/9.9", requests.single().headers[HttpHeaders.UserAgent])
        assertNull(requests.single().headers[HttpHeaders.Authorization])

        requests.clear()
        client.animesByIds((1..120).toList() + 1)
        assertEquals(listOf(50, 50, 20), requests.map { it.url.parameters["ids"]!!.split(',').size })
    }

    @Test fun detailsCarryStudiosScreenshotsAndTheNextEpisode() = runTest {
        val client = client { respond(fixture("anime_details.json"), headers = headers) }
        val details = client.anime(60000)
        assertEquals("MAPPA", details.studios.first().name)
        assertEquals(2, details.screenshots.size)
        assertEquals("2026-09-14T17:00:00.000+03:00", details.nextEpisodeAt)
        assertEquals("/api/animes/60000", requests.single().url.encodedPath)
    }

    @Test fun libraryRatesSendEveryFilterAndPageEveryStatusWithTheBearer() = runTest {
        val client = client { request ->
            val status = request.url.parameters["status"]!!
            val page = request.url.parameters["page"]!!.toInt()
            respond(
                if (status == "watching" && page == 1) fixture("user_rates.json")
                else if (status == "completed" && page == 1) (1..1000).joinToString(",", "[", "]") { """{"id":$it,"target_id":$it,"status":"completed","episodes":1,"updated_at":"2026-09-01T00:00:00.000+03:00"}""" }
                else "[]",
                headers = headers,
            )
        }
        val rates = client.libraryRates(42, "secret")
        assertEquals(1002, rates.size)
        assertEquals(52991, rates[0].targetId)
        assertEquals(52991, rates[0].animeId)
        assertEquals(20, rates[0].episodes)
        val first = requests.first()
        assertEquals("/api/v2/user_rates", first.url.encodedPath)
        assertEquals("Anime", first.url.parameters["target_type"])
        assertEquals("42", first.url.parameters["user_id"])
        assertEquals("1000", first.url.parameters["limit"])
        assertEquals("Bearer secret", first.headers[HttpHeaders.Authorization])
        assertEquals(ShikimoriClient.STATUSES, requests.map { it.url.parameters["status"] }.distinct())
        assertEquals(listOf(1, 2), requests.filter { it.url.parameters["status"] == "completed" }.map { it.url.parameters["page"]!!.toInt() })
    }

    @Test fun anOlderRateShapeNamesItsAnimeByTheEmbeddedCard() {
        val rate = restJson.decodeFromString(UserRateDto.serializer(), """{"id":9,"status":"rewatching","episodes":7,"anime":{"id":2000,"name":"Again"}}""")
        assertEquals(2000, rate.animeId)
        assertEquals("Again", rate.embedded?.name)
        assertNull(rate.updatedAt)
    }

    @Test fun rateWritesAreWrappedAndNameOnlyWhatTheyChange() = runTest {
        val client = client { respond(fixture("user_rates.json").removePrefix("[").substringBefore("},") + "}", headers = headers) }
        client.updateUserRate(111, episodes = 21, token = "secret")
        val update = requests.single()
        assertEquals(HttpMethod.Patch, update.method)
        assertEquals("/api/v2/user_rates/111", update.url.encodedPath)
        assertEquals("""{"user_rate":{"episodes":21}}""", update.text())
        assertEquals(ContentType.Application.Json, update.body.contentType?.withoutParameters())
        assertEquals("Bearer secret", update.headers[HttpHeaders.Authorization])

        requests.clear()
        client.updateUserRate(111, status = "on_hold", token = "secret")
        assertEquals("""{"user_rate":{"status":"on_hold"}}""", requests.single().text())

        requests.clear()
        client.createUserRate(42, 52991, "watching", episodes = 1, token = "secret")
        val create = requests.single()
        assertEquals(HttpMethod.Post, create.method)
        assertEquals("/api/v2/user_rates", create.url.encodedPath)
        assertEquals("""{"user_rate":{"user_id":42,"target_id":52991,"target_type":"Anime","status":"watching","episodes":1}}""", create.text())

        requests.clear()
        client.createUserRate(42, 52991, "planned", token = "secret")
        assertEquals("""{"user_rate":{"user_id":42,"target_id":52991,"target_type":"Anime","status":"planned"}}""", requests.single().text())
    }

    @Test fun whoamiUsesTheUsersEndpointAndStaysAnonymousWithoutAToken() = runTest {
        val client = client { respond("""{"id":42,"nickname":"frog","avatar":null,"ignored":true}""", headers = headers) }
        val user = client.whoami("")
        assertEquals(42L, user.id)
        assertEquals("frog", user.nickname)
        assertNull(user.avatar)
        assertEquals("/api/users/whoami", requests.single().url.encodedPath)
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test fun screenshotsSearchAndCatalogueUseTheirDocumentedRoutes() = runTest {
        val client = client { request ->
            if (request.url.encodedPath.endsWith("/screenshots")) respond("""[{"original":"/one.jpg","preview":"/one-preview.jpg"}]""", headers = headers)
            else respond(fixture("animes_list.json"), headers = headers)
        }
        assertEquals("/one.jpg", client.screenshots(60000).single().original)
        assertEquals(2, client.search("frog show").size)
        client.catalogue(status = "ongoing")
        client.catalogue(season = "summer_2026")
        assertEquals("/api/animes/60000/screenshots", requests[0].url.encodedPath)
        assertEquals("/api/animes", requests[1].url.encodedPath)
        assertEquals("frog show", requests[1].url.parameters["search"])
        assertEquals("30", requests[1].url.parameters["limit"])
        assertEquals(setOf("order", "limit", "censored", "status"), requests[2].url.parameters.names())
        assertEquals("ongoing", requests[2].url.parameters["status"])
        assertEquals("popularity", requests[2].url.parameters["order"])
        assertEquals("20", requests[2].url.parameters["limit"])
        assertEquals("true", requests[2].url.parameters["censored"])
        assertEquals(setOf("order", "limit", "censored", "season"), requests[3].url.parameters.names())
        assertEquals("summer_2026", requests[3].url.parameters["season"])
    }

    @Test fun postersPreferOriginalOverMainAndSurviveAFailedBatch() = runTest {
        var batches = 0
        val client = client { request ->
            batches++
            val query = request.text()
            if (batches == 1) respond("unavailable", HttpStatusCode.InternalServerError)
            else respond("""{"data":{"animes":[{"id":"51","poster":{"mainUrl":"/small.webp","originalUrl":"//cdn.example/large.webp"}},{"id":"52","poster":{"mainUrl":"/only-main.webp","originalUrl":""}},{"id":"999","poster":{"mainUrl":"/unrequested.webp"}}]}}""", headers = headers)
                .also { assertTrue(query.contains("limit: 50")) }
        }
        val posters = client.posters((1..52).toList())
        assertEquals(mapOf(51 to "https://cdn.example/large.webp", 52 to "https://shikimori.io/only-main.webp"), posters)
        assertEquals(2, batches)
        assertTrue(client.posters(emptyList()).isEmpty())
        assertEquals(2, batches)
    }

    @Test fun theRestPosterIsTheCurrentFieldThenTheLegacyImage() {
        assertEquals("https://cdn.example/p.webp", AnimeDto(1, poster = PosterDto(originalUrl = "https://cdn.example/p.webp"), image = ImageDto("/o.jpg")).restPoster)
        assertEquals("https://shikimori.io/o.jpg", AnimeDto(1, image = ImageDto("/o.jpg", "/p.jpg")).restPoster)
        assertEquals("https://shikimori.io/p.jpg", AnimeDto(1, image = ImageDto(null, "/p.jpg")).restPoster)
        assertNull(AnimeDto(1).restPoster)
        assertEquals("https://shikimori.io/images/poster.jpg", shikimoriUrl("images/poster.jpg"))
        assertEquals("https://cdn.example/x.jpg", shikimoriUrl("//cdn.example/x.jpg"))
    }

    @Test fun tokensGoToTheProxyAsAFormWithTheRedirectTheCallerNames() = runTest {
        val client = client { respond("""{"access_token":"acc","refresh_token":"ref","expires_in":86400,"created_at":1757600000}""", headers = headers) }
        val tokens = client.token("authorization_code", "abc", "urn:ietf:wg:oauth:2.0:oob")
        assertEquals("acc", tokens.accessToken)
        assertEquals("ref", tokens.refreshToken)
        assertEquals(86400, tokens.expiresIn)
        val exchange = requests.single()
        assertEquals("proxy.example", exchange.url.host)
        assertEquals("/oauth/token", exchange.url.encodedPath)
        assertEquals("grant_type=authorization_code&client_id=cid&code=abc&redirect_uri=urn%3Aietf%3Awg%3Aoauth%3A2.0%3Aoob", exchange.text())
        assertNull(exchange.headers[HttpHeaders.Authorization])

        requests.clear()
        client.token("refresh_token", "ref")
        assertEquals("grant_type=refresh_token&client_id=cid&refresh_token=ref", requests.single().text())
    }

    @Test fun aBuildWithoutAUsableProxyKnowsItAndSendsNothing() = runTest {
        for (proxy in listOf("", "   ", "kaeru-relay.workers.dev", "wss://kaeru-relay.workers.dev", "not a url at all")) {
            val client = client(proxy = proxy) { error("Must not contact network") }
            assertFalse(client.oauthConfigured, proxy)
            assertFailsWith<IllegalArgumentException> { client.token("refresh_token", "ref") }
        }
        assertTrue(client(proxy = "https://proxy.example/base") { error("unused") }.oauthConfigured)
    }

    @Test fun aRefusalCarriesItsStatusAndNoAnswerIsANetworkFailure() = runTest {
        assertEquals(422, assertFailsWith<ApiException> { client { respond("nope", HttpStatusCode.UnprocessableEntity) }.anime(1) }.status)
        assertFailsWith<NetworkException> { client { throw Exception("connection refused") }.anime(1) }
        assertEquals("Invalid API response.", assertFailsWith<Exception> { client { respond("<html>", headers = headers) }.anime(1) }.message)
    }
}
