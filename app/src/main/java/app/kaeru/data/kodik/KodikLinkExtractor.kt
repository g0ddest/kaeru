package app.kaeru.data.kodik

import app.kaeru.di.KodikPlayerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Walks the browser half of the Kodik chain: fetch a player page, then ask
 * `/ftor` for the signed HLS links of one episode.
 *
 * Kodik gates both steps on looking like its own embed: a desktop browser
 * `User-Agent`, and a `Referer` that is the page the request came from. The
 * signing parameters (`d_sign`, `pd_sign`, `ref_sign`) live on the page and
 * expire after a few hours, so a page is always fetched right before use rather
 * than cached.
 */
@Singleton
class KodikLinkExtractor @Inject constructor(
    @param:KodikPlayerClient private val client: OkHttpClient,
    @param:Named("kodikPlayerHost") private val playerHost: String,
) {

    /**
     * @param link the `link` field of a `get-player` answer, e.g.
     *   `//kodikplayer.com/serial/53973/<hash>/720p`. Scheme-relative and
     *   absolute forms are both accepted, and the host is always replaced by
     *   the configured [playerHost].
     */
    suspend fun loadPage(link: String, season: Int? = null, episode: Int? = null): KodikPlayerPage =
        fetchPage(playerUrl(link, season, episode), referer = "$playerHost/")

    /**
     * The page of one translation: its `data-media-id`/`data-media-hash` name a
     * different serial entry with its own episode list and its own signatures.
     */
    suspend fun loadPageForMedia(
        page: KodikPlayerPage,
        mediaId: String,
        mediaHash: String,
        type: String = "seria",
        season: Int? = null,
        episode: Int? = null,
    ): KodikPlayerPage {
        val path = "/${pathSegmentFor(type)}/$mediaId/$mediaHash/720p"
        return fetchPage(playerUrl(path, season, episode), referer = page.sourceUrl ?: "$playerHost/")
    }

    /**
     * Asks `/ftor` for the links of one episode and decodes them.
     *
     * Defaults to the episode the page is currently showing, which is the only
     * option for a movie; for a serial the caller passes the `data-id`/
     * `data-hash` of the episode it wants from [KodikPlayerPage.episodes].
     *
     * @return quality height (360/480/720/1080) -> HLS manifest URL.
     */
    suspend fun resolveLinks(
        page: KodikPlayerPage,
        mediaId: String = page.currentId,
        mediaHash: String = page.currentHash,
        type: String = page.currentType,
    ): Map<Int, String> {
        val referer = page.sourceUrl ?: "$playerHost/"
        val body = FormBody.Builder()
            .add("d", page.domain)
            .add("d_sign", page.dSign)
            .add("pd", page.pd)
            .add("pd_sign", page.pdSign)
            // Already decoded by the parser. Encoding it here a second time is
            // the classic way to make Kodik answer "invalid signature".
            .add("ref", page.ref)
            .add("ref_sign", page.refSign)
            .add("type", type)
            .add("hash", mediaHash)
            .add("id", mediaId)
            .add("bad_user", "false")
            .add("cdn_is_working", "true")
            .build()
        val request = Request.Builder()
            .url(playerUrl(page.ftorPath, season = null, episode = null))
            .post(body)
            .header("User-Agent", KodikConstants.BROWSER_UA)
            .header("Referer", referer)
            .header("Origin", playerHost.trimEnd('/'))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val json = execute(request)
        return try {
            KodikLinkDecoder.decode(json)
        } catch (e: KodikError) {
            throw e
        } catch (e: Exception) {
            // Kodik answers 200 with an HTML interstitial when it dislikes the
            // request; that is a format change, not a transport failure.
            throw KodikError.ParserBroken("ftor", e)
        }
    }

    private suspend fun fetchPage(url: HttpUrl, referer: String): KodikPlayerPage {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KodikConstants.BROWSER_UA)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        return KodikHtmlParser.parse(execute(request)).copy(sourceUrl = url.toString())
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw KodikError.Network(
                        IOException("${request.url.encodedPath} answered HTTP ${response.code}"),
                    )
                }
                response.body.string()
            }
        } catch (e: IOException) {
            throw KodikError.Network(e)
        }
    }

    /** Kodik serves serials under `/serial/...` and everything else under the `vInfo.type` name. */
    private fun pathSegmentFor(type: String): String = if (type == "seria") "serial" else type

    private fun playerUrl(link: String, season: Int?, episode: Int?): HttpUrl {
        val host = playerHost.toHttpUrlOrNull() ?: throw KodikError.ParserBroken("player-host")
        val absolute = when {
            link.startsWith("//") -> "https:$link"
            link.startsWith("http://") || link.startsWith("https://") -> link
            link.startsWith("/") -> playerHost.trimEnd('/') + link
            else -> throw KodikError.ParserBroken("player-link")
        }
        val parsed = absolute.toHttpUrlOrNull() ?: throw KodikError.ParserBroken("player-link")
        return parsed.newBuilder()
            .scheme(host.scheme)
            .host(host.host)
            .port(host.port)
            .apply {
                season?.let { setQueryParameter("season", it.toString()) }
                episode?.let { setQueryParameter("episode", it.toString()) }
            }
            .build()
    }
}
