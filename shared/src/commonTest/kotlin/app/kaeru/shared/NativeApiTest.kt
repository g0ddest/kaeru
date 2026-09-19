package app.kaeru.shared

import app.kaeru.shared.data.shikimori.ShikimoriRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeApiTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private fun TestScope.api(handler: MockRequestHandler): NativeApi = NativeApi(
        "public-client", "https://proxy.example/base/", HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/graphql") respond("""{"data":{"animes":[]}}""", headers = headers)
            else handler(request)
        }),
        ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime }),
    )
    private fun String.obj() = Json.parseToJsonElement(this).jsonObject
    private fun String.array() = Json.parseToJsonElement(this).jsonArray
    private fun HttpRequestData.text() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test fun searchIsPublicEncodesQueryAndEmitsCompleteNonNullSchema() = runTest {
        val api = api { request ->
            assertEquals("/api/animes", request.url.encodedPath)
            assertEquals("フリーレン & friends", request.url.parameters["search"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers[HttpHeaders.UserAgent]!!.startsWith("Kaeru/"))
            respond("""[{"id":52991,"name":"Frieren","russian":null,"score":8.9,"image":{"original":"/poster.jpg"},"aired_on":"2023-09-29","episodes_aired":null}]""", headers = headers)
        }
        try {
            val anime = api.search("フリーレン & friends").array().single().jsonObject
            assertEquals("Frieren", anime["title"]!!.jsonPrimitive.content)
            assertEquals("https://shikimori.io/poster.jpg", anime["poster"]!!.jsonPrimitive.content)
            assertEquals("2023", anime["year"]!!.jsonPrimitive.content)
            assertEquals("8.9", anime["score"]!!.jsonPrimitive.content)
            assertEquals(0, anime["episodesAired"]!!.jsonPrimitive.int)
            assertEquals(setOf("id", "title", "originalTitle", "poster", "description", "episodes", "episodesAired", "status", "score", "year", "nextEpisodeAt"), anime.keys)
            assertFalse(anime.values.any { it == JsonNull })
        } finally { api.close() }
    }

    @Test fun discoverLoadsPopularOngoingAndReleasedAndDeduplicates() = runTest {
        val statuses = mutableListOf<String>()
        val api = api { request ->
            statuses += request.url.parameters["status"]!!
            assertEquals("popularity", request.url.parameters["order"])
            assertEquals("true", request.url.parameters["censored"])
            respond(if (statuses.size == 1) """[{"id":1},{"id":2}]""" else """[{"id":2},{"id":3}]""", headers = headers)
        }
        try {
            assertEquals(listOf(1, 2, 3), api.discover().array().map { it.jsonObject["id"]!!.jsonPrimitive.int })
            assertEquals(listOf("ongoing", "released"), statuses)
        } finally { api.close() }
    }

    @Test fun detailsAndAccountNormalizeUpstreamFields() = runTest {
        val api = api { request ->
            when (request.url.encodedPath) {
                "/api/animes/42" -> respond("""{"id":42,"name":"Original","russian":"Название","image":{"preview":"//cdn.example/p.jpg"},"description":"Story","next_episode_at":"2026-10-01T10:00:00Z","episodes":12,"episodes_aired":8}""", headers = headers)
                "/api/users/whoami" -> {
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    respond("""{"id":5000000000,"nickname":"Viewer","avatar":"/avatar.jpg"}""", headers = headers)
                }
                else -> error("Unexpected request")
            }
        }
        try {
            val anime = api.details(42).obj()
            assertEquals("Название", anime["title"]!!.jsonPrimitive.content)
            assertEquals("Original", anime["originalTitle"]!!.jsonPrimitive.content)
            assertEquals("https://cdn.example/p.jpg", anime["poster"]!!.jsonPrimitive.content)
            assertEquals("Story", anime["description"]!!.jsonPrimitive.content)
            assertEquals("2026-10-01T10:00:00Z", anime["nextEpisodeAt"]!!.jsonPrimitive.content)
            val account = api.account("secret").obj()
            assertEquals(5000000000L, account["id"]!!.jsonPrimitive.long)
            assertEquals("https://shikimori.io/avatar.jpg", account["avatar"]!!.jsonPrimitive.content)
        } finally { api.close() }
    }

    @Test fun libraryPaginatesEveryStatusHydratesMissingCardsInBatchesAndKeepsLongIds() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val hydrated = mutableListOf<Int>()
        val api = api { request ->
            when (request.url.encodedPath) {
                "/api/v2/user_rates" -> {
                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                    assertEquals("5000000000", request.url.parameters["user_id"])
                    assertEquals("Anime", request.url.parameters["target_type"])
                    assertEquals("1000", request.url.parameters["limit"])
                    val status = request.url.parameters["status"]!!
                    val page = request.url.parameters["page"]!!.toInt()
                    calls += status to page
                    val body = when {
                        status == "watching" && page == 1 -> (1..1000).joinToString(",", "[", "]") {
                            """{"id":${5000000000L + it},"target_id":$it,"status":"watching","episodes":2,"target":{"id":$it,"name":"Embedded $it"}}"""
                        }
                        status == "watching" && page == 2 -> (1001..1051).joinToString(",", "[", "]") {
                            """{"id":${5000000000L + it},"target_id":$it,"status":"watching","episodes":3}"""
                        }
                        status == "rewatching" -> """[{"id":9000000000,"status":"rewatching","episodes":7,"anime":{"id":2000,"name":"Again"}}]"""
                        else -> "[]"
                    }
                    respond(body, headers = headers)
                }
                "/api/animes" -> {
                    val ids = request.url.parameters["ids"]!!.split(',').map(String::toInt)
                    assertTrue(ids.size <= 50)
                    assertNull(request.headers[HttpHeaders.Authorization])
                    hydrated += ids
                    respond(ids.joinToString(",", "[", "]") { """{"id":$it,"name":"Hydrated $it"}""" }, headers = headers)
                }
                else -> error("Unexpected request")
            }
        }
        try {
            val library = api.library(5000000000, "access").array()
            assertEquals(1052, library.size)
            assertEquals(setOf("planned", "watching", "rewatching", "completed", "on_hold", "dropped"), calls.map { it.first }.toSet())
            assertTrue("watching" to 2 in calls)
            assertEquals((1001..1051).toList(), hydrated)
            val hydratedItem = library.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.long == 5000001001L }
            assertEquals("Hydrated 1001", hydratedItem["anime"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally { api.close() }
    }

    @Test fun oauthUsesConfiguredBasePathFormContentLengthAndSnakeCaseResponse() = runTest {
        val grants = mutableListOf<String>()
        val api = api { request ->
            assertEquals("proxy.example", request.url.host)
            assertEquals("/base/oauth/token", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            val form = request.text().parseUrlEncodedParameters()
            assertEquals("public-client", form["client_id"])
            assertEquals(request.text().encodeToByteArray().size.toLong(), request.body.contentLength)
            assertNull(form["client_secret"])
            grants += form["grant_type"]!!
            if (form["grant_type"] == "authorization_code") {
                assertEquals("c+&日本", form["code"])
                assertEquals("kaeru://oauth", form["redirect_uri"])
            } else assertEquals("r+&", form["refresh_token"])
            respond("""{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600,"created_at":123,"scope":"user_rates"}""", headers = headers)
        }
        try {
            assertEquals("access", api.exchange("c+&日本").obj()["access_token"]!!.jsonPrimitive.content)
            assertEquals("refresh", api.refresh("r+&").obj()["refresh_token"]!!.jsonPrimitive.content)
            assertEquals(listOf("authorization_code", "refresh_token"), grants)
        } finally { api.close() }
    }

    @Test fun oauthGrantRejectionExportsOnlySafeMarker() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
            val api = api {
                respond("""{"error":"invalid_grant","error_description":"private-refresh-token","access_token":"private-access-token"}""", status, headers)
            }
            try {
                for (exchange in listOf(false, true)) {
                    val error = assertFailsWith<Exception> {
                        if (exchange) api.exchange("private-code") else api.refresh("private-refresh-token")
                    }
                    assertEquals("OAuth failed (HTTP ${status.value}): invalid_grant.", error.message)
                    assertNull(error.cause)
                    assertFalse(error.toString().contains("private-"))
                }
            } finally { api.close() }
        }
    }

    @Test fun oauthOtherFailuresKeepStatusOnly() = runTest {
        val bodies = listOf(
            "unknown client",
            "invalid_grant",
            """{"error":"invalid_client","error_description":"invalid_grant private-token"}""",
            """{"error":"INVALID_GRANT"}""",
            """{"error":"invalid_grant "}""",
            """{"error":"prefix invalid_grant"}""",
            """{"error":invalid_grant}""",
            """{"error":null}""",
            """{"error":401}""",
            """{"error":{"error":"invalid_grant"}}""",
            """[{"error":"invalid_grant"}]""",
            """{"error_description":"invalid_grant private-token"}""",
            """{"error":"invalid_grant",""",
        )
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
            for (body in bodies) {
                val api = api { respond(body, status, headers) }
                try {
                    val error = assertFailsWith<Exception> { api.refresh("private-token") }
                    assertEquals(
                        if (status == HttpStatusCode.Unauthorized) "Authentication failed (HTTP 401); refresh the session and retry once."
                        else "Request failed (HTTP 400).",
                        error.message,
                    )
                    assertNull(error.cause)
                } finally { api.close() }
            }
        }
    }

    @Test fun oauthGrantMarkerRequires400Or401() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            val api = api { respond("""{"error":"invalid_grant"}""", status, headers) }
            try {
                val error = assertFailsWith<Exception> { api.refresh("private-token") }
                assertEquals("Request failed (HTTP ${status.value}).", error.message)
            } finally { api.close() }
        }
    }

    @Test fun nonOauthFailuresDoNotExportGrantMarker() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
            val api = api { respond("""{"error":"invalid_grant"}""", status, headers) }
            try {
                val error = assertFailsWith<Exception> { api.account("private-token") }
                assertFalse(error.message!!.contains("invalid_grant"))
                assertTrue(error.message!!.contains("HTTP ${status.value}"))
            } finally { api.close() }
        }
    }

    @Test fun absentOrInvalidProxyFailsBeforeSendingCredentials() = runTest {
        for (proxy in listOf("", "wss://relay.example", "not a url")) {
            val api = NativeApi("client", proxy, HttpClient(MockEngine { error("Must not contact network") }))
            try { assertFailsWith<Exception> { api.exchange("secret") } } finally { api.close() }
        }
    }

    @Test fun createsAndUpdatesRatesWithRequiredPayloadAndHydratedAnime() = runTest {
        val writes = mutableListOf<HttpMethod>()
        val api = api { request ->
            if (request.url.encodedPath == "/api/animes/42") {
                respond("""{"id":42,"name":"Title"}""", headers = headers)
            } else {
                assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                writes += request.method
                val payload = request.text().obj()["user_rate"]!!.jsonObject
                assertEquals("watching", payload["status"]!!.jsonPrimitive.content)
                assertEquals(3, payload["episodes"]!!.jsonPrimitive.int)
                if (request.method == HttpMethod.Post) {
                    assertEquals("/api/v2/user_rates", request.url.encodedPath)
                    assertEquals(5000000000, payload["user_id"]!!.jsonPrimitive.long)
                    assertEquals(42, payload["target_id"]!!.jsonPrimitive.int)
                    assertEquals("Anime", payload["target_type"]!!.jsonPrimitive.content)
                } else assertEquals("/api/v2/user_rates/6000000000", request.url.encodedPath)
                respond("""{"id":6000000000,"target_id":42,"status":"watching","episodes":3}""", headers = headers)
            }
        }
        try {
            for (id in listOf(0L, 6000000000L)) {
                val item = api.setRate(42, 5000000000, id, "watching", 3, "access").obj()
                assertEquals(6000000000, item["id"]!!.jsonPrimitive.long)
                assertEquals("Title", item["anime"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            }
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Patch), writes)
        } finally { api.close() }
    }

    @Test fun unauthorizedIsDescriptiveDoesNotLeakBodyAndDoesNotRefreshImplicitly() = runTest {
        var calls = 0
        val api = api {
            calls++
            respond("secret-access-token", HttpStatusCode.Unauthorized, headers)
        }
        try {
            val error = assertFailsWith<Exception> { api.account("secret-access-token") }
            assertTrue(error.message!!.contains("401"))
            assertTrue(error.message!!.contains("auth", ignoreCase = true))
            assertFalse(error.toString().contains("secret-access-token"))
            assertEquals(1, calls)
        } finally { api.close() }
    }

    @Test fun rateLimitResponseRetriesOnceThenFailsWithoutLeakingBody() = runTest {
        var calls = 0
        val api = api {
            calls++
            respond("sensitive response", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        try {
            val error = assertFailsWith<Exception> { api.search("test") }
            assertEquals(2, calls)
            assertTrue(error.message!!.contains("429"))
            assertFalse(error.toString().contains("sensitive response"))
            assertTrue(testScheduler.currentTime >= 1000)
        } finally { api.close() }
    }

    @Test fun cancellationPropagatesAndServerErrorsDoNotBecomeEmptyResults() = runTest {
        val cancelled = api { throw CancellationException("cancelled") }
        try { assertFailsWith<CancellationException> { cancelled.search("test") } } finally { cancelled.close() }
        val failed = api { respond("private", HttpStatusCode.InternalServerError) }
        try { assertFailsWith<Exception> { failed.library(1, "access") } } finally { failed.close() }
    }
}
