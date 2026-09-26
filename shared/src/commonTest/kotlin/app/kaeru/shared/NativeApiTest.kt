package app.kaeru.shared

import app.kaeru.shared.data.shikimori.ShikimoriRateLimiter
import app.kaeru.shared.data.network.wireJson
import app.kaeru.shared.domain.Anime
import app.kaeru.shared.domain.LibraryItem
import app.kaeru.shared.domain.Translation
import kotlinx.serialization.encodeToString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

    @Test fun screenshotsGiveAbsoluteFullSizeFramesAndPreviewOnlyAsFallback() = runTest {
        val api = api { request ->
            assertEquals("/api/animes/21/screenshots", request.url.encodedPath)
            respond("""[{"original":"/system/screenshots/original/a.jpg","preview":"/system/screenshots/x332/a.jpg"},{"preview":"/system/screenshots/x332/b.jpg"},{}]""", headers = headers)
        }
        try {
            assertEquals(
                listOf("https://shikimori.io/system/screenshots/original/a.jpg", "https://shikimori.io/system/screenshots/x332/b.jpg"),
                api.screenshots(21).array().map { it.jsonPrimitive.content },
            )
        } finally { api.close() }
    }

    @Test fun discoverLoadsOnlyPopularOngoing() = runTest {
        val statuses = mutableListOf<String>()
        val api = api { request ->
            statuses += request.url.parameters["status"]!!
            assertEquals("popularity", request.url.parameters["order"])
            assertEquals("true", request.url.parameters["censored"])
            assertEquals("20", request.url.parameters["limit"])
            assertNull(request.url.parameters["season"])
            assertNull(request.url.parameters["page"])
            respond(if (statuses.size == 1) """[{"id":1},{"id":2}]""" else """[{"id":2},{"id":3}]""", headers = headers)
        }
        try {
            assertEquals(listOf(1, 2), api.discover().array().map { it.jsonObject["id"]!!.jsonPrimitive.int })
            assertEquals(listOf("ongoing"), statuses)
        } finally { api.close() }
    }

    @Test fun seasonalUsesAndroidFiltersAndReturnsEnrichedCards() = runTest {
        val requests = mutableListOf<String>()
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath == "/api/graphql") {
                respond("""{"data":{"animes":[{"id":"42","poster":{"mainUrl":"https://cdn.example/42.jpg"}}]}}""", headers = headers)
            } else {
                assertEquals("/api/animes", request.url.encodedPath)
                assertEquals(setOf("season", "order", "censored", "limit"), request.url.parameters.names())
                assertEquals("autumn_2026", request.url.parameters["season"])
                assertEquals("popularity", request.url.parameters["order"])
                assertEquals("true", request.url.parameters["censored"])
                assertEquals("20", request.url.parameters["limit"])
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("""[{"id":42,"kind":"tv"}]""", headers = headers)
            }
        }), ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime }))
        try {
            val card = api.seasonal(2026, "autumn").array().single().jsonObject
            assertEquals(42, card["id"]!!.jsonPrimitive.int)
            assertEquals("tv", card["kind"]?.jsonPrimitive?.content)
            assertEquals("https://cdn.example/42.jpg", card["poster"]!!.jsonPrimitive.content)
            assertEquals(listOf("/api/animes", "/api/graphql"), requests)
        } finally { api.close() }
    }

    @Test fun seasonalRejectsInvalidInputsBeforeNetwork() = runTest {
        val api = api { error("Invalid season must not contact network") }
        try {
            for ((year, season) in listOf(0 to "winter", -1 to "spring", 2026 to "", 2026 to "fall", 2026 to "winter,summer")) {
                assertFailsWith<IllegalArgumentException> { api.seasonal(year, season) }
            }
        } finally { api.close() }
    }

    @Test fun seasonalSharesRateLimiterAndCancellationDoesNotBlockLaterRequests() = runTest {
        val sent = mutableListOf<Long>()
        val api = api {
            sent += testScheduler.currentTime
            respond("[]", headers = headers)
        }
        try {
            repeat(5) { api.discover() }
            val waiting = async { api.seasonal(2026, "winter") }
            runCurrent()
            assertEquals(5, sent.size)
            waiting.cancelAndJoin()
            assertEquals("[]", api.seasonal(2026, "spring"))
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1000L), sent)
        } finally { api.close() }
    }

    @Test fun seasonalNetworkCancellationPropagates() = runTest {
        val api = api { throw CancellationException("cancelled") }
        try { assertFailsWith<CancellationException> { api.seasonal(2026, "summer") } }
        finally { api.close() }
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

    @Test fun optionalMetadataDecodesLegacyAndExplicitNullWirePayloads() {
        for (metadata in listOf("", ",\"kind\":null,\"studios\":null")) {
            val anime = wireJson.decodeFromString<Anime>("""{"id":42$metadata}""")
            assertNull(anime.kind)
            assertNull(anime.studios)
            assertFalse(wireJson.encodeToString(anime).obj().containsKey("kind"))
            assertFalse(wireJson.encodeToString(anime).obj().containsKey("studios"))
        }
        for (metadata in listOf("", ",\"updatedAt\":null")) {
            val item = wireJson.decodeFromString<LibraryItem>("""{"id":1,"anime":{"id":42},"status":"watching","episodes":0$metadata}""")
            assertNull(item.updatedAt)
            assertFalse(wireJson.encodeToString(item).obj().containsKey("updatedAt"))
        }
        for (metadata in listOf("", ",\"kind\":null")) {
            val translation = wireJson.decodeFromString<Translation>("""{"id":1,"title":"Track"$metadata}""")
            assertNull(translation.kind)
            assertEquals(setOf("id", "title", "episodes"), wireJson.encodeToString(translation).obj().keys)
        }
    }

    @Test fun detailsExposeOptionalKindAndStudioNames() = runTest {
        val api = api {
            respond("""{"id":42,"kind":"tv","studios":[{"id":1,"name":"Madhouse"},{"id":2,"name":"Bones"}]}""", headers = headers)
        }
        try {
            val anime = api.details(42).obj()
            assertEquals("tv", anime["kind"]?.jsonPrimitive?.content)
            assertEquals(listOf("Madhouse", "Bones"), anime["studios"]?.jsonArray?.map { it.jsonPrimitive.content })
        } finally { api.close() }
    }

    @Test fun libraryPreservesUpdatedAtForEmbeddedAndHydratedCards() = runTest {
        val api = api { request ->
            val body = when {
                request.url.encodedPath == "/api/animes" -> """[{"id":43,"kind":"movie"}]"""
                request.url.parameters["status"] == "watching" -> """[
                    {"id":1,"target_id":42,"status":"watching","episodes":2,"updated_at":"2026-09-19T12:34:56Z","target":{"id":42}},
                    {"id":2,"target_id":43,"status":"watching","episodes":1,"updated_at":"2026-09-18T12:34:56Z"},
                    {"id":3,"target_id":42,"status":"watching","episodes":0,"updated_at":null}
                ]"""
                else -> "[]"
            }
            respond(body, headers = headers)
        }
        try {
            val items = api.library(1, "access").array().map { it.jsonObject }
            assertEquals("2026-09-19T12:34:56Z", items[0]["updatedAt"]?.jsonPrimitive?.content)
            assertEquals("2026-09-18T12:34:56Z", items[1]["updatedAt"]?.jsonPrimitive?.content)
            assertEquals("movie", items[1]["anime"]!!.jsonObject["kind"]?.jsonPrimitive?.content)
            assertNull(items[2]["updatedAt"]?.jsonPrimitive?.contentOrNull)
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
                respond("""{"id":6000000000,"target_id":42,"status":"watching","episodes":3,"updated_at":"2026-09-19T12:34:56Z"}""", headers = headers)
            }
        }
        try {
            for (id in listOf(0L, 6000000000L)) {
                val item = api.setRate(42, 5000000000, id, "watching", 3, "access").obj()
                assertEquals(6000000000, item["id"]!!.jsonPrimitive.long)
                assertEquals("Title", item["anime"]!!.jsonObject["title"]!!.jsonPrimitive.content)
                assertEquals("2026-09-19T12:34:56Z", item["updatedAt"]?.jsonPrimitive?.content)
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
