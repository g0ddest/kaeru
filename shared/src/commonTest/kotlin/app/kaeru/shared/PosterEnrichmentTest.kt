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
class PosterEnrichmentTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private fun TestScope.api(handler: MockRequestHandler) = NativeApi(
        "client", "", HttpClient(MockEngine { request -> handler(request) }),
        ShikimoriRateLimiter { testScheduler.currentTime },
    )
    private fun String.obj() = Json.parseToJsonElement(this).jsonObject
    private fun String.cards() = Json.parseToJsonElement(this).jsonArray.map { it.jsonObject }
    private fun JsonObject.poster() = getValue("poster").jsonPrimitive.content
    private fun HttpRequestData.query(): String {
        assertEquals("/api/graphql", url.encodedPath)
        assertEquals(HttpMethod.Post, method)
        assertNull(this.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, body.contentType)
        return (body as OutgoingContent.ByteArrayContent).bytes().decodeToString().obj().getValue("query").jsonPrimitive.content
    }
    private fun card(id: Int) = """{"id":$id,"name":"Title $id","image":{"original":"/legacy/$id.jpg"}}"""

    @Test fun searchMapsPostersByIdPrefersMainAndKeepsMissingRestFallback() = runTest {
        val api = api { request ->
            if (request.url.encodedPath == "/api/graphql") {
                assertEquals("{ animes(ids: \"1,2,3\", limit: 50) { id poster { mainUrl originalUrl } } }", request.query())
                respond("""{"data":{"animes":[{"id":"2","poster":{"mainUrl":null,"originalUrl":"//cdn.example/2.webp"}},{"id":"1","poster":{"mainUrl":"https://cdn.example/1.webp","originalUrl":"https://cdn.example/large1.webp"}},{"id":"999","poster":{"mainUrl":"https://cdn.example/unrequested.webp"}}]}}""", headers = headers)
            } else respond(listOf(1, 2, 3, 1).joinToString(",", "[", "]", transform = ::card), headers = headers)
        }
        try {
            val cards = api.search("title").cards()
            assertEquals(listOf("https://cdn.example/1.webp", "https://cdn.example/2.webp", "https://shikimori.io/legacy/3.jpg", "https://cdn.example/1.webp"), cards.map { it.poster() })
            assertEquals(listOf("Title 1", "Title 2", "Title 3", "Title 1"), cards.map { it.getValue("title").jsonPrimitive.content })
        } finally { api.close() }
    }

    @Test fun discoverEnrichesTheDeduplicatedCatalogue() = runTest {
        var queries = 0
        val api = api { request ->
            if (request.url.encodedPath == "/api/graphql") {
                queries++
                assertEquals("{ animes(ids: \"1,2,3\", limit: 50) { id poster { mainUrl originalUrl } } }", request.query())
                respond("""{"data":{"animes":[{"id":"3","poster":{"mainUrl":"/uploads/3.webp"}}]}}""", headers = headers)
            } else {
                assertEquals("ongoing", request.url.parameters["status"])
                val ids = listOf(1, 2, 2, 3)
                respond(ids.joinToString(",", "[", "]", transform = ::card), headers = headers)
            }
        }
        try {
            val cards = api.discover().cards()
            assertEquals(listOf(1, 2, 3), cards.map { it.getValue("id").jsonPrimitive.int })
            assertEquals("https://shikimori.io/uploads/3.webp", cards.last().poster())
            assertEquals(1, queries)
        } finally { api.close() }
    }

    @Test fun detailsAndRateResultUseEnrichedPoster() = runTest {
        val api = api { request ->
            when (request.url.encodedPath) {
                "/api/graphql" -> {
                    assertEquals("{ animes(ids: \"42\", limit: 50) { id poster { mainUrl originalUrl } } }", request.query())
                    respond("""{"data":{"animes":[{"id":"42","poster":{"mainUrl":"https://cdn.example/42.webp"}}]}}""", headers = headers)
                }
                "/api/animes/42" -> respond(card(42), headers = headers)
                "/api/v2/user_rates" -> respond("""{"id":123,"status":"planned","episodes":0}""", headers = headers)
                else -> error("Unexpected request")
            }
        }
        try {
            assertEquals("https://cdn.example/42.webp", api.details(42).obj().poster())
            assertEquals("https://cdn.example/42.webp", api.setRate(42, 1, 0, "planned", 0, "access").obj().getValue("anime").jsonObject.poster())
        } finally { api.close() }
    }

    @Test fun libraryBatchesEmbeddedAndHydratedCardsAndContinuesAfterFailedBatch() = runTest {
        for (failFirstBatch in listOf(false, true)) {
            val queries = mutableListOf<List<Int>>()
            val times = mutableListOf<Long>()
            val api = api { request ->
                times += testScheduler.currentTime
                when (request.url.encodedPath) {
                    "/api/v2/user_rates" -> respond(
                        if (request.url.parameters["status"] == "watching") (1..101).joinToString(",", "[", "]") { id ->
                            val target = if (id <= 100) ",\"target\":${card(id)}" else ""
                            """{"id":$id,"target_id":$id,"status":"watching","episodes":2$target}"""
                        } else "[]", headers = headers,
                    )
                    "/api/animes" -> {
                        assertEquals("101", request.url.parameters["ids"])
                        respond("[${card(101)}]", headers = headers)
                    }
                    "/api/graphql" -> {
                        val query = request.query()
                        val ids = query.substringAfter("ids: \"").substringBefore('"').split(',').map(String::toInt)
                        assertTrue(query.contains("limit: 50"))
                        queries += ids
                        if (failFirstBatch && queries.size == 1) respond("unavailable", HttpStatusCode.InternalServerError)
                        else respond(ids.reversed().joinToString(",", "{\"data\":{\"animes\":[", "]}}") {
                            """{"id":"$it","poster":{"mainUrl":"https://cdn.example/$it.webp"}}"""
                        }, headers = headers)
                    }
                    else -> error("Unexpected request")
                }
            }
            try {
                val items = api.library(1, "access").cards()
                assertEquals(listOf((1..50).toList(), (51..100).toList(), listOf(101)), queries)
                assertEquals(101, items.size)
                items.forEachIndexed { index, item ->
                    val id = index + 1
                    val expected = if (failFirstBatch && id <= 50) "https://shikimori.io/legacy/$id.jpg" else "https://cdn.example/$id.webp"
                    assertEquals(expected, item.getValue("anime").jsonObject.poster())
                }
                for (time in times) assertTrue(times.count { it > time - 1000 && it <= time } <= 5)
            } finally { api.close() }
        }
    }

    @Test fun graphqlFailuresRetainRestCatalogues() = runTest {
        val answers = listOf(
            HttpStatusCode.InternalServerError to "private-upstream-body",
            HttpStatusCode.Unauthorized to "unauthorized",
            HttpStatusCode.OK to "<html>unavailable</html>",
            HttpStatusCode.OK to """{"errors":[{"message":"unavailable"}]}""",
            HttpStatusCode.OK to """{"data":null}""",
            HttpStatusCode.OK to """{"data":{"animes":[{"id":"1","poster":null},{"id":"2","poster":{"mainUrl":"","originalUrl":""}}]}}""",
        )
        for ((status, body) in answers) {
            val api = api { request ->
                if (request.url.encodedPath == "/api/graphql") respond(body, status, headers)
                else respond("[${card(1)},${card(2)}]", headers = headers)
            }
            try {
                assertEquals(listOf("https://shikimori.io/legacy/1.jpg", "https://shikimori.io/legacy/2.jpg"), api.search("title").cards().map { it.poster() })
            } finally { api.close() }
        }
    }

    @Test fun graphqlNetworkFailureRetainsRestButCancellationPropagates() = runTest {
        for (cancel in listOf(false, true)) {
            val api = api { request ->
                if (request.url.encodedPath == "/api/graphql") {
                    if (cancel) throw CancellationException("cancelled")
                    else throw Exception("private-network-details")
                }
                respond("[${card(1)}]", headers = headers)
            }
            try {
                if (cancel) assertFailsWith<CancellationException> { api.search("title") }
                else assertEquals("https://shikimori.io/legacy/1.jpg", api.search("title").cards().single().poster())
            } finally { api.close() }
        }
    }

    @Test fun emptyCatalogueDoesNotRequestPosters() = runTest {
        val paths = mutableListOf<String>()
        val api = api { request -> paths += request.url.encodedPath; respond("[]", headers = headers) }
        try {
            assertTrue(api.search("unknown").cards().isEmpty())
            assertEquals(listOf("/api/animes"), paths)
        } finally { api.close() }
    }
}
