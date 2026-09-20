package app.kaeru.shared.data.kodik

import app.kaeru.shared.ApiException
import app.kaeru.shared.TestFixtures
import app.kaeru.shared.data.network.HttpTransport
import app.kaeru.shared.data.network.NetworkException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The client as Android drives it: the catalogue it keeps, the lists it remembers, the seasons it
 * opens, and where its token comes from. The chain itself — pages, `/ftor`, the decoded links —
 * is covered through [app.kaeru.shared.NativeApi] in [KodikNetworkTest].
 */
class KodikClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun fixture(name: String) = TestFixtures.text("kodik/$name")

    /** The wall clock the client reads; tests move it by hand instead of sleeping. */
    private var now = 1_757_700_000_000L

    private class Routes {
        var getPlayerCalls = 0
        var scripts = 0
        var scriptBody = "var token=\"token1\";"
        var playerPageBody: String? = null
        var playerPageStatus = HttpStatusCode.OK
        var ftorBody: String? = null
        var lastFtorForm: Parameters? = null
        val getPlayerAnswers = ArrayDeque<Pair<String, HttpStatusCode>>()
        val tokensSeen = mutableListOf<String>()
        val requests = mutableListOf<HttpRequestData>()
        var unreachable = false
    }

    private fun HttpRequestData.form(): Parameters =
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    private fun MockRequestHandleScope.dispatch(routes: Routes, request: HttpRequestData): HttpResponseData {
        routes.requests += request
        if (routes.unreachable) throw Exception("connection refused")
        return when (request.url.host) {
            "kodik-add.com" -> { routes.scripts++; respond(routes.scriptBody) }
            "kodik-api.com" -> {
                routes.getPlayerCalls++
                routes.tokensSeen += request.form()["token"]!!
                val (body, status) = routes.getPlayerAnswers.removeFirstOrNull() ?: (FOUND to HttpStatusCode.OK)
                respond(body, status, jsonHeaders)
            }
            else -> if (request.url.encodedPath == "/ftor") {
                routes.lastFtorForm = request.form()
                respond(routes.ftorBody ?: fixture("links.json"), headers = jsonHeaders)
            } else respond(routes.playerPageBody ?: fixture("player.html"), routes.playerPageStatus)
        }
    }

    private class FakeCache : KodikTokenCache {
        var entry: KodikTokenCache.Entry? = null
        var stores = 0
        var clears = 0
        override suspend fun load(): KodikTokenCache.Entry? = entry
        override suspend fun store(token: String, storedAtMillis: Long) { entry = KodikTokenCache.Entry(token, storedAtMillis); stores++ }
        override suspend fun clear() { entry = null; clears++ }
    }

    private fun client(
        routes: Routes,
        cache: KodikTokenCache = KodikTokenCache.None,
        configured: suspend () -> String? = { null },
    ) = KodikClient(
        HttpTransport(HttpClient(MockEngine { request -> dispatch(routes, request) })),
        tokenCache = cache, configuredToken = configured, nowMillis = { now },
    )

    private fun singleTrackFilm(routes: Routes) { routes.playerPageBody = fixture("movie-single-track.html") }

    // --- the catalogue ---------------------------------------------------------------------------

    @Test fun translationsListEveryTrackOfThePlayerPage() = runTest {
        val tracks = client(Routes()).translations(ANIME)
        assertEquals(33, tracks.size)
        val first = tracks.first()
        assertEquals(3560, first.id)
        assertTrue(first.title.startsWith("#студияБУБНЯЖА"))
        assertEquals(TranslationType.VOICE, first.type)
        assertEquals(28, first.episodesCount)
        assertTrue(tracks.any { it.type == TranslationType.SUBTITLES })
    }

    @Test fun aFilmWithNoChooserOffersTheOneTrackItsPageNamesAndPlaysIt() = runTest {
        val routes = Routes().also(::singleTrackFilm)
        val kodik = client(routes)
        val only = kodik.translations(ANIME).single()
        assertEquals(923, only.id)
        assertEquals("AnimeVost", only.title)
        assertEquals(TranslationType.VOICE, only.type)
        assertEquals(1, only.episodesCount)
        val stream = kodik.resolve(ANIME, 0, 1)
        assertEquals(1, stream.episode)
        assertEquals(923, stream.translation.id)
        assertTrue(stream.urls.isNotEmpty())
        // The one track of such a film is also the one a remembered choice matches.
        assertEquals(923, kodik.resolve(ANIME, 923, 1).translation.id)
    }

    @Test fun getPlayerCarriesTheShikimoriIdTheTokenAndTheAnimeTypes() = runTest {
        val routes = Routes()
        client(routes).translations(ANIME)
        val form = routes.requests.single { it.url.host == "kodik-api.com" }.form()
        assertEquals("token1", form["token"])
        assertEquals(ANIME.toString(), form["shikimoriID"])
        assertEquals("anime,anime-serial", form["types"])
    }

    @Test fun aTitleKodikHasNoPlayerForIsMissingAsATitle() = runTest {
        for (answer in listOf("""{"found":false}""", """{"found":true}""")) {
            val routes = Routes().apply { getPlayerAnswers += answer to HttpStatusCode.OK }
            val error = assertFailsWith<KodikError.NotFound> { client(routes).translations(ANIME) }
            assertEquals(KodikError.Missing.TITLE, error.missing)
        }
    }

    @Test fun theCatalogueIsKeptForSixHoursPerAnimeAndNeverAfterAFailure() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        kodik.translations(ANIME)
        now += 5 * HOUR + 59 * MINUTE
        kodik.translations(ANIME)
        assertEquals(1, routes.getPlayerCalls)
        now += MINUTE + 1000
        kodik.translations(ANIME)
        assertEquals(2, routes.getPlayerCalls)
        kodik.translations(ANIME + 1)
        assertEquals(3, routes.getPlayerCalls)

        routes.getPlayerAnswers += """{"found":false}""" to HttpStatusCode.OK
        assertFailsWith<KodikError.NotFound> { kodik.translations(ANIME + 2) }
        assertEquals(33, kodik.translations(ANIME + 2).size)
        assertEquals(5, routes.getPlayerCalls)
    }

    @Test fun aClockThatWentBackwardsReReadsTheCatalogue() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        kodik.translations(ANIME)
        now -= 1000
        kodik.translations(ANIME)
        assertEquals(2, routes.getPlayerCalls)
    }

    @Test fun resolveReusesThePlayerPageTheListingFetched() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        kodik.translations(ANIME)
        kodik.resolve(ANIME, 0, 1)
        assertEquals(1, routes.getPlayerCalls)
    }

    // --- resolving ------------------------------------------------------------------------------

    @Test fun resolveOpensTheChosenTracksPageForTheSeasonAndEpisodeAsked() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        val chosen = kodik.translations(ANIME)[1]
        val stream = kodik.resolve(ANIME, chosen.id, 4)
        val page = routes.requests.single { it.url.encodedPath == "/serial/${chosen.mediaId}/${chosen.mediaHash}/720p" }
        assertEquals("1", page.url.parameters["season"])
        assertEquals("4", page.url.parameters["episode"])
        assertEquals(1, stream.season)
        assertEquals(chosen, stream.translation)

        val remembered = kodik.resolve(ANIME, chosen.id, 1, season = 2)
        val second = routes.requests.last { it.url.encodedPath.startsWith("/serial/${chosen.mediaId}/") }
        assertEquals("2", second.url.parameters["season"])
        assertEquals(2, remembered.season)
    }

    @Test fun resolveWithoutATrackTakesTheFirstOneAndEveryDecodedQuality() = runTest {
        val stream = client(Routes()).resolve(ANIME, 0, 1)
        assertEquals(3560, stream.translation.id)
        assertEquals(1, stream.episode)
        assertEquals(setOf(360, 480, 720), stream.urls.keys)
        stream.urls.values.forEach { assertTrue(it.startsWith("https://")) }
        assertEquals(KodikClient.PLAYER_HOST, stream.headers["Origin"])
        assertEquals(KodikClient.BROWSER_UA, stream.headers["User-Agent"])
        assertTrue(stream.headers["Referer"]!!.startsWith("https://kodikplayer.com/serial/"))
    }

    @Test fun anEpisodeOrTrackKodikDoesNotHaveIsMissingAsAnEpisode() = runTest {
        val kodik = client(Routes())
        assertEquals(KodikError.Missing.EPISODE, assertFailsWith<KodikError.NotFound> { kodik.resolve(ANIME, 0, 99) }.missing)
        assertEquals(KodikError.Missing.EPISODE, assertFailsWith<KodikError.NotFound> { kodik.resolve(ANIME, -1, 1) }.missing)
    }

    @Test fun pageRequestsLookLikeTheEmbedAndFtorQuotesThePageItCameFrom() = runTest {
        val routes = Routes()
        client(routes).resolve(ANIME, 0, 7)
        val pages = routes.requests.filter { it.url.host == "kodikplayer.com" && it.url.encodedPath != "/ftor" }
        assertEquals(2, pages.size)
        for (page in pages) {
            assertEquals(KodikClient.BROWSER_UA, page.headers[HttpHeaders.UserAgent])
            assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", page.headers[HttpHeaders.Accept])
        }
        assertEquals("${KodikClient.PLAYER_HOST}/", pages[0].headers[HttpHeaders.Referrer])
        assertEquals(pages[0].url.toString(), pages[1].headers[HttpHeaders.Referrer])
        val ftor = routes.requests.single { it.url.encodedPath == "/ftor" }
        assertEquals(pages[1].url.toString(), ftor.headers[HttpHeaders.Referrer])
        assertEquals("application/json, text/javascript, */*; q=0.01", ftor.headers[HttpHeaders.Accept])
        assertEquals(ContentType.Application.FormUrlEncoded, ftor.body.contentType?.withoutParameters())
        val form = routes.lastFtorForm!!
        assertEquals("kodikplayer.com", form["d"])
        assertEquals("https://kodikplayer.com/", form["ref"])
        assertEquals("seria", form["type"])
        val episode = KodikHtmlParser.parse(fixture("player.html")).episodes.single { it.number == 7 }
        assertEquals(episode.mediaId, form["id"])
        assertEquals(episode.mediaHash, form["hash"])
    }

    // --- what a track's own page said it carries --------------------------------------------------

    @Test fun aTracksEpisodesAreKnownOnceItsPageHasBeenReadAndNotBefore() = runTest {
        val kodik = client(Routes())
        assertNull(kodik.listedEpisodes(ANIME, 3560))
        kodik.resolve(ANIME, 0, 1)
        assertEquals((1..28).toSet(), kodik.listedEpisodes(ANIME, 3560))
        assertNull(kodik.listedEpisodes(ANIME, 923))
    }

    @Test fun aMissingEpisodeStillLeavesTheTracksListBehind() = runTest {
        val kodik = client(Routes())
        assertFailsWith<KodikError.NotFound> { kodik.resolve(ANIME, 0, 99) }
        assertEquals((1..28).toSet(), kodik.listedEpisodes(ANIME, 3560))
    }

    @Test fun forgettingATitleDropsTheCatalogueAndWhatItsTracksListed() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        kodik.resolve(ANIME, 0, 1)
        kodik.forget(ANIME)
        assertNull(kodik.listedEpisodes(ANIME, 3560))
        kodik.translations(ANIME)
        assertEquals(2, routes.getPlayerCalls)
    }

    @Test fun aCatalogueThatExpiredTakesTheListsWithIt() = runTest {
        val kodik = client(Routes())
        kodik.resolve(ANIME, 0, 1)
        now += 6 * HOUR + 1000
        assertNull(kodik.listedEpisodes(ANIME, 3560))
    }

    // --- failures --------------------------------------------------------------------------------

    @Test fun aRedesignedPlayerPageIsAParserFailure() = runTest {
        val routes = Routes().apply { playerPageBody = "<html><body>redesigned</body></html>" }
        assertTrue(assertFailsWith<KodikError.ParserBroken> { client(routes).translations(ANIME) }.step.isNotBlank())
    }

    @Test fun aHostThatAnswersWithAnErrorIsARefusalCarryingItsStatus() = runTest {
        val api = Routes().apply { getPlayerAnswers += """{"error":"oops"}""" to HttpStatusCode.ServiceUnavailable }
        assertEquals(503, assertFailsWith<ApiException> { client(api).translations(ANIME) }.status)
        assertEquals(1, api.getPlayerCalls)

        val player = Routes().apply { playerPageStatus = HttpStatusCode.Forbidden }
        assertEquals(403, assertFailsWith<ApiException> { client(player).translations(ANIME) }.status)
    }

    @Test fun anFtorAnswerThatIsNotJsonIsAParserFailure() = runTest {
        val routes = Routes().apply { ftorBody = "<html>blocked</html>" }
        assertEquals("links", assertFailsWith<KodikError.ParserBroken> { client(routes).resolve(ANIME, 0, 1) }.step)
    }

    @Test fun anUnreachableKodikIsANetworkFailure() = runTest {
        val routes = Routes().apply { unreachable = true }
        assertFailsWith<NetworkException> { client(routes).translations(ANIME) }
    }

    // --- where the token comes from ----------------------------------------------------------------

    @Test fun aKeyTheAppHoldsWinsOverTheScriptAndIsReadOnEveryCall() = runTest {
        val routes = Routes()
        var held: String? = "from-settings"
        val kodik = client(routes, configured = { held })
        kodik.translations(ANIME)
        held = "  from-buildconfig  "
        kodik.forget(ANIME)
        kodik.translations(ANIME)
        assertEquals(listOf("from-settings", "from-buildconfig"), routes.tokensSeen)
        assertEquals(0, routes.scripts)
    }

    @Test fun aBlankKeyFallsThroughToTheScript() = runTest {
        val routes = Routes()
        client(routes, configured = { "   " }).translations(ANIME)
        assertEquals(listOf("token1"), routes.tokensSeen)
        assertEquals(1, routes.scripts)
    }

    @Test fun aRejectedKeyIsTriedOnceMoreAndThenGivenUpOnWithoutTouchingTheScript() = runTest {
        val routes = Routes().apply {
            getPlayerAnswers += REJECTED to HttpStatusCode.Unauthorized
            getPlayerAnswers += REJECTED to HttpStatusCode.OK
        }
        val cache = FakeCache()
        assertFailsWith<KodikError.NoToken> { client(routes, cache, configured = { "private" }).translations(ANIME) }
        assertEquals(listOf("private", "private"), routes.tokensSeen)
        assertEquals(0, routes.scripts)
        assertEquals(0, cache.clears)
    }

    @Test fun theScriptTokenIsScrapedOnceAndReusedForADay() = runTest {
        val routes = Routes()
        val kodik = client(routes)
        kodik.translations(ANIME)
        now += 23 * HOUR + 59 * MINUTE
        kodik.forget(ANIME)
        kodik.translations(ANIME)
        assertEquals(1, routes.scripts)
        now += MINUTE + 1000
        routes.scriptBody = "var token=\"token2\";"
        kodik.forget(ANIME)
        kodik.translations(ANIME)
        assertEquals(2, routes.scripts)
        assertEquals(listOf("token1", "token1", "token2"), routes.tokensSeen)
    }

    @Test fun theScriptRequestLooksLikeTheEmbed() = runTest {
        val routes = Routes()
        client(routes).translations(ANIME)
        val script = routes.requests.single { it.url.host == "kodik-add.com" }
        assertEquals("/add-players.min.js", script.url.encodedPath)
        assertEquals("2", script.url.parameters["v"])
        assertEquals(KodikClient.BROWSER_UA, script.headers[HttpHeaders.UserAgent])
        assertEquals("${KodikClient.PLAYER_HOST}/", script.headers[HttpHeaders.Referrer])
    }

    @Test fun aScrapedTokenIsWrittenToTheCacheAndACachedOneSurvivesANewClient() = runTest {
        val routes = Routes()
        val cache = FakeCache()
        client(routes, cache).translations(ANIME)
        assertEquals("token1", cache.entry?.token)
        assertEquals(now, cache.entry?.storedAtMillis)
        client(routes, cache).translations(ANIME)
        assertEquals(1, routes.scripts)
        assertEquals(listOf("token1", "token1"), routes.tokensSeen)
    }

    @Test fun aCachedTokenOlderThanADayOrFromTheFutureIsScrapedAgain() = runTest {
        for (storedAt in listOf(now - 24 * HOUR - 1000, now + 1000)) {
            val routes = Routes()
            val cache = FakeCache().apply { entry = KodikTokenCache.Entry("stale", storedAt) }
            client(routes, cache).translations(ANIME)
            assertEquals(1, routes.scripts)
            assertEquals(listOf("token1"), routes.tokensSeen)
            assertEquals("token1", cache.entry?.token)
        }
    }

    @Test fun aCachedTokenKodikRejectsIsClearedAndScrapedAfresh() = runTest {
        val routes = Routes().apply { getPlayerAnswers += REJECTED to HttpStatusCode.Unauthorized }
        val cache = FakeCache().apply { entry = KodikTokenCache.Entry("stale", now - HOUR) }
        client(routes, cache).translations(ANIME)
        assertEquals(listOf("stale", "token1"), routes.tokensSeen)
        assertEquals(1, cache.clears)
        assertEquals("token1", cache.entry?.token)
    }

    @Test fun aScriptWithoutATokenOrWithAnErrorStatusMeansNoKeyNotNoNetwork() = runTest {
        val silent = Routes().apply { scriptBody = "var x = 1;" }
        assertFailsWith<KodikError.NoToken> { client(silent).translations(ANIME) }
        val refused = Routes().apply { scriptBody = "" }
        val kodik = KodikClient(HttpTransport(HttpClient(MockEngine { request ->
            if (request.url.host == "kodik-add.com") respond("nope", HttpStatusCode.ServiceUnavailable) else dispatch(refused, request)
        })), nowMillis = { now })
        assertFailsWith<KodikError.NoToken> { kodik.translations(ANIME) }
    }

    private companion object {
        const val ANIME = 52991
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val FOUND = """{"found":true,"allowed":1,"quality":"720p","translation":"AniLibria.TV","link":"//kodikplayer.com/serial/53973/cf62e729fdb71a0b7fb148ba6fc48ad6/720p"}"""
        const val REJECTED = """{"error":"Отсутствует или неверный токен"}"""
    }
}
