package app.kaeru.shared.data.kodik

import app.kaeru.shared.data.network.*
import app.kaeru.shared.domain.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

internal class KodikClient(private val http: HttpTransport) {
    private val tokenLock = Mutex()
    private var publicToken: String? = null
    private var tokenAt: kotlin.time.TimeMark? = null

    suspend fun translations(animeId: Int): List<Translation> = catalogue(animeId).translations.map { it.toDomain() }

    suspend fun resolve(animeId: Int, translationId: Int, episode: Int): Stream {
        require(episode > 0) { "Episode must be positive." }
        val catalogue = catalogue(animeId)
        val chosen = (if (translationId == 0) catalogue.translations.firstOrNull()
            else catalogue.translations.firstOrNull { it.id == translationId }) ?: throw KodikError.NotFound()
        val serial = catalogue.currentType == "seria"
        val type = if (serial) "serial" else catalogue.currentType
        // A track has its own media id, hash, episode list and signing parameters. Never cache this page.
        val url = playerUrl("/$type/${chosen.mediaId}/${chosen.mediaHash}/720p", episode.takeIf { serial })
        val page = loadPage(url, catalogue.sourceUrl ?: "$PLAYER_HOST/")
        val wanted = page.episodes.firstOrNull { it.number == episode }
        if (page.episodes.isNotEmpty() && wanted == null) throw KodikError.NotFound()
        val form = Parameters.build {
            append("d", page.domain); append("d_sign", page.dSign)
            append("pd", page.pd); append("pd_sign", page.pdSign)
            append("ref", page.ref); append("ref_sign", page.refSign)
            append("type", if (wanted != null) "seria" else page.currentType)
            append("hash", wanted?.mediaHash ?: page.currentHash)
            append("id", wanted?.mediaId ?: page.currentId)
            append("bad_user", "false"); append("cdn_is_working", "true")
        }
        val body = http.request(playerUrl(page.ftorPath)) {
            browserHeaders(url)
            method = HttpMethod.Post
            header(HttpHeaders.Origin, PLAYER_HOST)
            header("X-Requested-With", "XMLHttpRequest")
            header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
            setBody(FormDataContent(form))
        }.successfulBody()
        val links = KodikLinkDecoder.decode(body)
        return Stream(
            urls = links.entries.sortedBy { it.key }.map { StreamUrl(it.key, it.value) },
            headers = mapOf("Referer" to url, "Origin" to PLAYER_HOST, "User-Agent" to BROWSER_UA),
            translation = chosen.toDomain(),
            episode = if (page.episodes.isEmpty()) 1 else episode,
        )
    }

    private suspend fun catalogue(animeId: Int): KodikPlayerPage {
        require(animeId > 0) { "Anime id must be positive." }
        val answer = getPlayer(animeId)
        val link = answer.string("link")
        if ((answer["found"] as? JsonPrimitive)?.booleanOrNull != true || link.isBlank()) throw KodikError.NotFound()
        val page = loadPage(playerUrl(link), "$PLAYER_HOST/")
        if (page.translations.isNotEmpty()) return page
        // Films with one track omit the chooser. Preserve the real id/title if the script names them.
        return page.copy(translations = listOf(KodikTranslationOption(
            id = page.currentTranslationId ?: -(page.currentId.toIntOrNull()?.takeIf { it > 0 } ?: 1),
            title = page.currentTranslationTitle ?: "Единственная озвучка",
            type = TranslationType.VOICE, episodesCount = 1,
            mediaId = page.currentId, mediaHash = page.currentHash,
        )))
    }

    private suspend fun getPlayer(animeId: Int): JsonObject {
        repeat(2) { attempt ->
            val token = token(force = attempt == 1)
            val response = http.request("https://kodik-api.com/get-player") {
                browserHeaders("$PLAYER_HOST/")
                method = HttpMethod.Post
                setBody(FormDataContent(Parameters.build {
                    append("token", token); append("shikimoriID", animeId.toString())
                    append("types", "anime,anime-serial")
                }))
            }
            if (response.status == 401) {
                invalidateToken(token)
                if (attempt == 1) throw KodikError.NoToken()
            } else {
                val answer = try { wireJson.parseToJsonElement(response.successfulBody()).jsonObject }
                    catch (e: HttpFailure) { throw e }
                    catch (_: Exception) { throw KodikError.ParserBroken("get-player") }
                val error = answer.string("error")
                if (error.contains("токен", true) || error.contains("token", true)) {
                    invalidateToken(token)
                    if (attempt == 1) throw KodikError.NoToken()
                } else return answer
            }
        }
        throw KodikError.NoToken()
    }

    private suspend fun token(force: Boolean): String = tokenLock.withLock {
        val age = tokenAt?.elapsedNow()?.inWholeMilliseconds
        if (!force && publicToken != null && age != null && age in 0 until 86_400_000) return@withLock publicToken!!
        publicToken = null
        tokenAt = null
        val response = http.request("https://kodik-add.com/add-players.min.js?v=2") { browserHeaders("$PLAYER_HOST/") }
        if (response.status !in 200..299) throw KodikError.NoToken()
        val fresh = KodikHtmlParser.extractPublicToken(response.body) ?: throw KodikError.NoToken()
        publicToken = fresh
        tokenAt = TimeSource.Monotonic.markNow()
        fresh
    }

    private suspend fun invalidateToken(rejected: String) = tokenLock.withLock {
        if (publicToken == rejected) { publicToken = null; tokenAt = null }
    }

    private suspend fun loadPage(url: String, referer: String): KodikPlayerPage {
        val body = http.request(url) {
            browserHeaders(referer)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.successfulBody()
        return KodikHtmlParser.parse(body).copy(sourceUrl = url)
    }

    private fun playerUrl(link: String, episode: Int? = null): String {
        val absolute = when {
            link.startsWith("//") -> "https:$link"
            link.startsWith("https://") || link.startsWith("http://") -> link
            link.startsWith("/") -> PLAYER_HOST + link
            else -> throw KodikError.ParserBroken("player-link")
        }
        val builder = try { URLBuilder(absolute) } catch (_: Exception) { throw KodikError.ParserBroken("player-link") }
        builder.protocol = URLProtocol.HTTPS
        builder.host = "kodikplayer.com"
        builder.port = 443
        builder.user = null
        builder.password = null
        if (episode != null) { builder.parameters["season"] = "1"; builder.parameters["episode"] = episode.toString() }
        return builder.buildString()
    }

    private fun HttpRequestBuilder.browserHeaders(referer: String) {
        header(HttpHeaders.UserAgent, BROWSER_UA)
        header(HttpHeaders.Referrer, referer)
    }

    private fun KodikTranslationOption.toDomain() = Translation(id, title, episodesCount ?: 0)

    private companion object {
        const val PLAYER_HOST = "https://kodikplayer.com"
        const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
    }
}
