package app.kaeru.shared.data.kodik

import app.kaeru.shared.NativeApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class KodikNetworkTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun String.obj() = Json.parseToJsonElement(this).jsonObject
    private fun fixture(name: String) = KodikFixtures.text(name)

    @Test fun resolutionSelectsEpisodeOnChosenTrackAndAlwaysObtainsFreshSignatures() = runTest {
        var scripts = 0
        var resolves = 0
        val forms = mutableListOf<Parameters>()
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            assertTrue(request.headers[HttpHeaders.UserAgent]!!.contains("Mozilla"))
            when (request.url.host) {
                "kodik-add.com" -> { scripts++; respond(fixture("add-players.js")) }
                "kodik-api.com" -> {
                    val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()
                    assertEquals("52991", form["shikimoriID"])
                    assertEquals("anime,anime-serial", form["types"])
                    respond("""{"found":true,"link":"//old-player.example/serial/53973/hash/720p"}""", headers = jsonHeaders)
                }
                "kodikplayer.com" -> when (request.url.encodedPath) {
                    "/serial/53973/hash/720p" -> respond(fixture("player.html"))
                    "/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" -> {
                        assertEquals("1", request.url.parameters["season"])
                        assertEquals("2", request.url.parameters["episode"])
                        assertTrue(request.headers[HttpHeaders.Referrer]!!.contains("/serial/53973/"))
                        respond(fixture("player.html").replace("var d_sign = \"", "var d_sign = \"fresh-"))
                    }
                    "/ftor" -> {
                        resolves++
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("https://kodikplayer.com", request.headers[HttpHeaders.Origin])
                        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                        assertTrue(request.headers[HttpHeaders.Referrer]!!.contains("/serial/55917/"))
                        forms += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()
                        respond(fixture("links.json"), headers = jsonHeaders)
                    }
                    else -> error("Unexpected player path ${request.url.encodedPath}")
                }
                else -> error("Unexpected host")
            }
        }))
        try {
            repeat(2) {
                val stream = api.resolve(52991, 3560, 2).obj()
                assertEquals(2, stream["episode"]!!.jsonPrimitive.int)
                assertEquals(3560, stream["translation"]!!.jsonObject["id"]!!.jsonPrimitive.int)
                assertEquals("voice", stream["translation"]!!.jsonObject["kind"]?.jsonPrimitive?.content)
                assertEquals(setOf(360, 480, 720), stream["urls"]!!.jsonArray.map { it.jsonObject["quality"]!!.jsonPrimitive.int }.toSet())
                assertEquals("https://kodikplayer.com", stream["headers"]!!.jsonObject["Origin"]!!.jsonPrimitive.content)
            }
            assertEquals(1, scripts)
            assertEquals(2, resolves)
            // Fixture episode 2 has its own id/hash, distinct from the page's current episode 1.
            assertTrue(forms.all { it["id"] != "1211482" && it["type"] == "seria" })
            assertTrue(forms.all { it["d_sign"]!!.startsWith("fresh-") })
            assertTrue(forms.all { it["ref"] == "https://kodikplayer.com/" })
            assertTrue(forms.all { it["bad_user"] == "false" && it["cdn_is_working"] == "true" })
        } finally { api.close() }
    }

    @Test fun singleVoiceMovieGetsRealTranslationAndResolvesAsEpisodeOne() = runTest {
        val paths = mutableListOf<String>()
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when {
                request.url.host == "kodik-add.com" -> respond(fixture("add-players.js"))
                request.url.host == "kodik-api.com" -> respond("""{"found":true,"link":"//kodikplayer.com/video/990011/aa11bb22cc33dd44ee55ff6677889900/720p"}""", headers = jsonHeaders)
                request.url.encodedPath == "/ftor" -> {
                    val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()
                    assertEquals("video", form["type"])
                    assertEquals("990011", form["id"])
                    respond(fixture("links.json"), headers = jsonHeaders)
                }
                else -> {
                    assertNull(request.url.parameters["episode"])
                    assertNull(request.url.parameters["season"])
                    respond(fixture("movie-single-track.html"))
                }
            }
        }))
        try {
            val tracks = Json.parseToJsonElement(api.translations(42)).jsonArray
            assertEquals("AnimeVost", tracks.single().jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("voice", tracks.single().jsonObject["kind"]?.jsonPrimitive?.content)
            assertEquals(1, tracks.single().jsonObject["episodes"]!!.jsonPrimitive.int)
            assertEquals(923, tracks.single().jsonObject["id"]!!.jsonPrimitive.int)
            val stream = api.resolve(42, 0, 4).obj()
            assertEquals(1, stream["episode"]!!.jsonPrimitive.int)
            assertTrue(paths.count { it.startsWith("/video/") } >= 3)
        } finally { api.close() }
    }

    @Test fun rejectedTokensUnder401Or200AreInvalidatedAndRetriedOnce() = runTest {
        for (unauthorizedStatus in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)) {
            var scripts = 0
            var players = 0
            val api = NativeApi("client", "", HttpClient(MockEngine { request ->
                when (request.url.host) {
                    "kodik-add.com" -> { scripts++; respond("var token=\"token$scripts\";") }
                    "kodik-api.com" -> {
                        players++
                        val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()
                        assertEquals("token$players", form["token"])
                        if (players == 1) respond("""{"error":"Отсутствует или неверный токен"}""", unauthorizedStatus, jsonHeaders)
                        else respond("""{"found":true,"link":"/video/990011/hash/720p"}""", headers = jsonHeaders)
                    }
                    else -> respond(fixture("movie-single-track.html"))
                }
            }))
            try {
                assertEquals(1, Json.parseToJsonElement(api.translations(42)).jsonArray.size)
                assertEquals(2, scripts)
                assertEquals(2, players)
            } finally { api.close() }
        }
    }

    @Test fun translationsExposeVoiceAndSubtitlesKinds() = runTest {
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            when (request.url.host) {
                "kodik-add.com" -> respond(fixture("add-players.js"))
                "kodik-api.com" -> respond("""{"found":true,"link":"/serial/53973/hash/720p"}""", headers = jsonHeaders)
                else -> respond(fixture("player.html"))
            }
        }))
        try {
            val tracks = Json.parseToJsonElement(api.translations(42)).jsonArray.map { it.jsonObject }
            assertEquals(setOf("voice", "subtitles"), tracks.mapNotNull { it["kind"]?.jsonPrimitive?.content }.toSet())
        } finally { api.close() }
    }

    @Test fun repeatedTokenRejectionStopsAfterOneRetry() = runTest {
        var calls = 0
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            if (request.url.host == "kodik-add.com") respond("token=\"abc\"")
            else { calls++; respond("""{"error":"invalid token"}""", headers = jsonHeaders) }
        }))
        try {
            assertFailsWith<KodikError.NoToken> { api.translations(42) }
            assertEquals(2, calls)
        } finally { api.close() }
    }

    @Test fun configuredTokenOverridesCachedPublicKeyAndBlankResetFetchesFreshKey() = runTest {
        var scripts = 0
        val sent = mutableListOf<String>()
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            when (request.url.host) {
                "kodik-add.com" -> { scripts++; respond("var token=\"public$scripts\";") }
                "kodik-api.com" -> {
                    sent += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()["token"]!!
                    respond("""{"found":true,"link":"/video/990011/hash/720p"}""", headers = jsonHeaders)
                }
                else -> respond(fixture("movie-single-track.html"))
            }
        }))
        try {
            api.translations(42)
            api.configureKodikToken("  private-first  ")
            api.translations(42)
            api.configureKodikToken("private-second")
            api.translations(42)
            api.configureKodikToken(" ")
            api.translations(42)
            api.translations(42)
            api.configureKodikToken("")
            api.translations(42)
            assertEquals(listOf("public1", "private-first", "private-second", "public2", "public2", "public3"), sent)
            assertEquals(3, scripts)
        } finally { api.close() }
    }

    @Test fun rejectedConfiguredTokenNeverFallsBackToPublicOrLeaksSecrets() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)) {
            var calls = 0
            val api = NativeApi("client", "", HttpClient(MockEngine { request ->
                assertEquals("kodik-api.com", request.url.host)
                calls++
                respond("""{"error":"invalid token private-key"}""", status, jsonHeaders)
            }))
            try {
                api.configureKodikToken("private-key")
                val error = assertFailsWith<KodikError.NoToken> { api.translations(42) }
                assertFalse(error.toString().contains("private-key"))
                assertNull(error.cause)
                assertEquals(1, calls)
            } finally { api.close() }
        }
    }

    @Test fun configurationDuringPublicFetchDiscardsStaleResultIncludingReset() = runTest {
        for (reset in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var scripts = 0
            val sent = mutableListOf<String>()
            val api = NativeApi("client", "", HttpClient(MockEngine { request ->
                when (request.url.host) {
                    "kodik-add.com" -> {
                        scripts++
                        if (scripts == 1) { started.complete(Unit); release.await() }
                        respond("var token=\"public$scripts\";")
                    }
                    "kodik-api.com" -> {
                        sent += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()["token"]!!
                        respond("""{"found":true,"link":"/video/990011/hash/720p"}""", headers = jsonHeaders)
                    }
                    else -> respond(fixture("movie-single-track.html"))
                }
            }))
            try {
                val pending = async { api.translations(42) }
                started.await()
                api.configureKodikToken("private-key")
                if (reset) api.configureKodikToken("")
                release.complete(Unit)
                pending.await()
                api.translations(42)
                assertEquals(if (reset) listOf("public2", "public2") else listOf("private-key", "private-key"), sent)
                assertEquals(if (reset) 2 else 1, scripts)
            } finally { release.complete(Unit); api.close() }
        }
    }

    @Test fun lateTokenRejectionDoesNotInvalidateNewConfiguration() = runTest {
        for (configured in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<String>()
            var scripts = 0
            val api = NativeApi("client", "", HttpClient(MockEngine { request ->
                when (request.url.host) {
                    "kodik-add.com" -> { scripts++; respond("var token=\"publickey\";") }
                    "kodik-api.com" -> {
                        sent += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()["token"]!!
                        if (sent.size == 1) {
                            started.complete(Unit)
                            release.await()
                            respond("rejected", HttpStatusCode.Unauthorized)
                        } else respond("""{"found":true,"link":"/video/990011/hash/720p"}""", headers = jsonHeaders)
                    }
                    else -> respond(fixture("movie-single-track.html"))
                }
            }))
            try {
                if (configured) api.configureKodikToken("old-private")
                val pending = async { api.translations(42) }
                started.await()
                api.configureKodikToken("new-private")
                api.translations(42)
                release.complete(Unit)
                pending.await()
                assertEquals(listOf(if (configured) "old-private" else "publickey", "new-private", "new-private"), sent)
                assertEquals(if (configured) 0 else 1, scripts)
            } finally { release.complete(Unit); api.close() }
        }
    }

    @Test fun cancellationDuringPublicFetchReleasesTokenLock() = runTest {
        val started = CompletableDeferred<Unit>()
        var scripts = 0
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            when (request.url.host) {
                "kodik-add.com" -> {
                    scripts++
                    if (scripts == 1) { started.complete(Unit); awaitCancellation() }
                    respond("var token=\"publickey\";")
                }
                "kodik-api.com" -> respond("""{"found":true,"link":"/video/990011/hash/720p"}""", headers = jsonHeaders)
                else -> respond(fixture("movie-single-track.html"))
            }
        }))
        try {
            val pending = async { api.translations(42) }
            started.await()
            pending.cancelAndJoin()
            assertEquals(1, Json.parseToJsonElement(api.translations(42)).jsonArray.size)
            assertEquals(2, scripts)
        } finally { api.close() }
    }

    @Test fun missingEpisodeOrTranslationNeverCallsFtor() = runTest {
        val api = NativeApi("client", "", HttpClient(MockEngine { request ->
            when {
                request.url.host == "kodik-add.com" -> respond(fixture("add-players.js"))
                request.url.host == "kodik-api.com" -> respond("""{"found":true,"link":"/serial/53973/hash/720p"}""", headers = jsonHeaders)
                request.url.encodedPath == "/ftor" -> error("Unavailable episode must not resolve")
                else -> respond(fixture("player.html"))
            }
        }))
        try {
            assertFailsWith<KodikError.NotFound> { api.resolve(42, 3560, 99) }
            assertFailsWith<KodikError.NotFound> { api.resolve(42, 999999, 1) }
        } finally { api.close() }
    }
}
