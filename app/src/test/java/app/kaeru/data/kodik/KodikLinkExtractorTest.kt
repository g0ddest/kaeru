package app.kaeru.data.kodik

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KodikLinkExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: KodikLinkExtractor

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("kodik/$name")!!.bufferedReader().readText()

    private fun playerPage() = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(fixture("player.html"))

    private fun ftorResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(fixture("links.json"))

    private fun parsedPage() = KodikHtmlParser.parse(fixture("player.html"))

    @Before
    fun setUp() {
        server.start()
        extractor = KodikLinkExtractor(
            client = OkHttpClient(),
            playerHost = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `loadPage turns a protocol-relative link into a request on the player host`() = runTest {
        server.enqueue(playerPage())

        val page = extractor.loadPage("//kodikplayer.com/serial/53973/cf62e729fdb71a0b7fb148ba6fc48ad6/720p")

        assertEquals("kodikplayer.com", page.domain)
        assertEquals("seria", page.currentType)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/serial/53973/cf62e729fdb71a0b7fb148ba6fc48ad6/720p", request.path)
    }

    @Test
    fun `loadPage rewrites an absolute kodik link onto the configured host`() = runTest {
        server.enqueue(playerPage())

        extractor.loadPage("https://kodikplayer.com/serial/53973/abc/720p")

        assertEquals("/serial/53973/abc/720p", server.takeRequest().path)
    }

    @Test
    fun `loadPage appends season and episode when they are given`() = runTest {
        server.enqueue(playerPage())

        extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p", season = 1, episode = 3)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/serial/53973/abc/720p", url.encodedPath)
        assertEquals("1", url.queryParameter("season"))
        assertEquals("3", url.queryParameter("episode"))
    }

    @Test
    fun `loadPage sends the browser user agent and the player referer`() = runTest {
        server.enqueue(playerPage())

        extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p")

        val request = server.takeRequest()
        assertEquals(KodikConstants.BROWSER_UA, request.getHeader("User-Agent"))
        assertEquals(server.url("/").toString(), request.getHeader("Referer"))
    }

    @Test
    fun `loadPage remembers the url it fetched so ftor can quote it as referer`() = runTest {
        server.enqueue(playerPage())

        val page = extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p", season = 1, episode = 2)

        assertEquals(
            server.url("/serial/53973/abc/720p?season=1&episode=2").toString(),
            page.sourceUrl,
        )
    }

    @Test
    fun `loadPageForMedia opens the serial page of the chosen translation`() = runTest {
        server.enqueue(playerPage())
        val translation = parsedPage().translations.first()

        extractor.loadPageForMedia(parsedPage(), translation.mediaId, translation.mediaHash, season = 1, episode = 3)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/serial/${translation.mediaId}/${translation.mediaHash}/720p", url.encodedPath)
        assertEquals("1", url.queryParameter("season"))
        assertEquals("3", url.queryParameter("episode"))
    }

    @Test
    fun `loadPageForMedia opens a video page for a movie`() = runTest {
        server.enqueue(playerPage())

        extractor.loadPageForMedia(parsedPage(), "9000", "moviehash", type = "video")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/video/9000/moviehash/720p", url.encodedPath)
        assertNull(url.queryParameter("season"))
    }

    @Test
    fun `loadPageForMedia quotes the page it came from as referer`() = runTest {
        server.enqueue(playerPage())
        server.enqueue(playerPage())
        val first = extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p")
        server.takeRequest()

        extractor.loadPageForMedia(first, "55917", "d1d44d5cd59af5af897ce899a776dacf", season = 1, episode = 1)

        assertEquals(first.sourceUrl, server.takeRequest().getHeader("Referer"))
    }

    @Test
    fun `resolveLinks posts every ftor field with ref encoded exactly once`() = runTest {
        server.enqueue(playerPage())
        server.enqueue(ftorResponse())
        val page = extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p", season = 1, episode = 1)
        server.takeRequest()

        extractor.resolveLinks(page)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/ftor", request.path)
        assertEquals(
            "d=kodikplayer.com" +
                "&d_sign=7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8%3A2609140747" +
                "&pd=kodikplayer.com" +
                "&pd_sign=7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8%3A2609140747" +
                "&ref=https%3A%2F%2Fkodikplayer.com%2F" +
                "&ref_sign=6137eaa1d4c94e3b6a15aaf56eb92ada806bda5bbec4784915459162b3ed622b%3A2609140747" +
                "&type=seria" +
                "&hash=cf62e729fdb71a0b7fb148ba6fc48ad6" +
                "&id=1211482" +
                "&bad_user=false" +
                "&cdn_is_working=true",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `resolveLinks sends the headers kodik checks before signing`() = runTest {
        server.enqueue(playerPage())
        server.enqueue(ftorResponse())
        val page = extractor.loadPage("//kodikplayer.com/serial/53973/abc/720p", season = 1, episode = 1)
        server.takeRequest()

        extractor.resolveLinks(page)

        val request = server.takeRequest()
        assertEquals(KodikConstants.BROWSER_UA, request.getHeader("User-Agent"))
        assertEquals(page.sourceUrl, request.getHeader("Referer"))
        assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals("application/json, text/javascript, */*; q=0.01", request.getHeader("Accept"))
        assertEquals(
            "application/x-www-form-urlencoded",
            request.getHeader("Content-Type")?.substringBefore(';'),
        )
    }

    @Test
    fun `resolveLinks decodes the manifest url of every quality`() = runTest {
        server.enqueue(ftorResponse())

        val links = extractor.resolveLinks(parsedPage())

        assertEquals(setOf(360, 480, 720), links.keys)
        links.values.forEach { url ->
            assertTrue("expected $url to be https", url.startsWith("https://"))
            assertTrue("expected $url to be an hls manifest", url.contains("manifest.m3u8"))
        }
    }

    @Test
    fun `resolveLinks posts the episode id and hash it is handed instead of the page defaults`() = runTest {
        server.enqueue(ftorResponse())
        val page = parsedPage()
        val episode = page.episodes.first { it.number == 5 }

        extractor.resolveLinks(page, mediaId = episode.mediaId, mediaHash = episode.mediaHash, type = "seria")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("expected the episode id in $body", body.contains("&id=${episode.mediaId}"))
        assertTrue("expected the episode hash in $body", body.contains("&hash=${episode.mediaHash}"))
    }

    @Test
    fun `a non-2xx player page fails with Network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = runCatching { extractor.loadPage("//kodikplayer.com/serial/1/h/720p") }.exceptionOrNull()

        assertTrue("expected Network, got $error", error is KodikError.Network)
    }

    @Test
    fun `a non-2xx ftor response fails with Network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))

        val error = runCatching { extractor.resolveLinks(parsedPage()) }.exceptionOrNull()

        assertTrue("expected Network, got $error", error is KodikError.Network)
    }

    @Test
    fun `an ftor response that is not json fails with ParserBroken ftor`() = runTest {
        server.enqueue(MockResponse().setBody("<html>blocked</html>"))

        val error = runCatching { extractor.resolveLinks(parsedPage()) }.exceptionOrNull()

        assertTrue("expected ParserBroken, got $error", error is KodikError.ParserBroken)
        assertEquals("ftor", (error as KodikError.ParserBroken).step)
    }

    @Test
    fun `an unusable player link fails with ParserBroken`() = runTest {
        val error = runCatching { extractor.loadPage("not a url at all") }.exceptionOrNull()

        assertTrue("expected ParserBroken, got $error", error is KodikError.ParserBroken)
        assertEquals("player-link", (error as KodikError.ParserBroken).step)
    }

    @Test
    fun `a page parsed straight from html has no source url`() {
        assertNull(parsedPage().sourceUrl)
    }
}
