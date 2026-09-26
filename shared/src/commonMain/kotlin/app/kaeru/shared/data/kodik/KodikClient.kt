package app.kaeru.shared.data.kodik

import app.kaeru.shared.ApiException
import app.kaeru.shared.data.network.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Signed, short-lived links for one episode on one track, and what a player has to send along
 * to be served them.
 *
 * [urls] is quality height to HLS manifest: Kodik serves one manifest per height rather than an
 * adaptive master playlist. [season] is the season the track's page was opened on, which is what
 * a caller that remembers seasons writes down.
 */
class KodikStream(
    val urls: Map<Int, String>,
    val headers: Map<String, String>,
    val translation: KodikTranslationOption,
    val episode: Int,
    val season: Int,
)

/**
 * The Kodik chain: `get-player` for a shikimori id gives a player page link, the page gives the
 * translation list and the signing parameters, and `/ftor` on a translation's own page gives the
 * signed HLS links of one episode.
 *
 * The catalogue — the player page and the tracks parsed off it — is kept for six hours per anime,
 * because it costs two requests and barely changes. The signed links are never kept: they expire
 * in hours and look bound to the IP that asked. Every translation's own page lists the episodes
 * of the season it was opened on, and that list is kept beside the catalogue for as long as the
 * catalogue is: it is what says, without another request, which tracks cannot have the episode a
 * viewer is about to be offered.
 *
 * The token every `kodik-api.com` call needs comes, in order, from [configuredToken] (a key the
 * app holds in its settings or its build), from [configureToken] (the same thing, set from Swift),
 * from the token scraped off Kodik's own embed script — remembered in memory and in [tokenCache]
 * for a day. Only the scraped token is re-scraped after a rejection: the other two are deliberate
 * configuration, and asking for them again would yield the same value.
 *
 * Shared by both platforms; [nowMillis] is the wall clock, injectable so a test can move it.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class KodikClient(
    private val http: HttpTransport,
    private val tokenCache: KodikTokenCache = KodikTokenCache.None,
    private val configuredToken: suspend () -> String? = { null },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val catalogueTtlMillis: Long = CATALOGUE_TTL_MILLIS,
) {
    private val tokenLock = Mutex()
    // Immutable snapshots allow the non-suspending Swift setter to invalidate an in-flight fetch.
    // This is deliberately not a data class: its string representation must never include keys.
    private class TokenState(
        val configured: String? = null,
        val automatic: String? = null,
        val at: Long? = null,
    ) {
        val value: String get() = configured ?: checkNotNull(automatic)
    }
    private val tokens = AtomicReference(TokenState())

    /** A player page plus when it was read, so [listed] can be trusted for exactly as long as it is. */
    private class Catalogue(val page: KodikPlayerPage, val at: Long)

    private val cacheLock = Mutex()
    private val cache = mutableMapOf<Int, Catalogue>()

    /** Per anime, per translation id: the episode numbers that track's page listed. Lives and dies with [cache]. */
    private val listed = mutableMapOf<Int, MutableMap<Int, Set<Int>>>()

    /** A trimmed, nonblank token overrides the scraped one; blank resets it and clears its cache. */
    fun configureToken(token: String) {
        val configured = token.trim().takeIf { it.isNotEmpty() }
        // Settings are saved often and send the same value each time. Only a different key starts
        // over; the same blank one would throw away the public key and fetch it again on the next
        // episode, a whole extra round trip to Kodik before the picture.
        if (tokens.load().configured == configured) return
        tokens.store(TokenState(configured = configured))
    }

    /** The tracks Kodik offers for an anime, in the order it lists them. */
    suspend fun translations(animeId: Int): List<KodikTranslationOption> = catalogue(animeId).translations

    /**
     * The episode numbers already seen listed for one track, or null when its page has never been
     * read. Memory only, never the network: a null is «not known», never «not there».
     */
    suspend fun listedEpisodes(animeId: Int, translationId: Int): Set<Int>? =
        cacheLock.withLock { cached(animeId)?.let { listed[animeId]?.get(translationId) } }

    /**
     * Forgets everything kept about this anime, so the next question reaches Kodik. A viewer
     * pressing «Повторить» on an episode that has since appeared must not be answered out of a
     * catalogue that predates it.
     */
    suspend fun forget(animeId: Int) {
        cacheLock.withLock { drop(animeId) }
    }

    /**
     * @param translationId a track from [translations], or 0 for the first one Kodik lists.
     * @param season the season the track's page is opened on. The page never says which season it
     *   lists, so a caller that remembers one outranks the default of 1.
     */
    suspend fun resolve(animeId: Int, translationId: Int, episode: Int, season: Int = 1): KodikStream {
        require(episode > 0) { "Episode must be positive." }
        val catalogue = catalogue(animeId)
        val chosen = (if (translationId == 0) catalogue.translations.firstOrNull()
            else catalogue.translations.firstOrNull { it.id == translationId })
            ?: throw KodikError.NotFound(KodikError.Missing.EPISODE)
        // A movie has no season or episode to select, and Kodik serves it from /video.
        val serial = catalogue.currentType == "seria"
        val type = if (serial) "serial" else catalogue.currentType
        // A track has its own media id, hash, episode list and signing parameters. Never cache this page.
        val url = playerUrl("/$type/${chosen.mediaId}/${chosen.mediaHash}/720p", season.takeIf { serial }, episode.takeIf { serial })
        val page = loadPage(url, catalogue.sourceUrl ?: "$PLAYER_HOST/")
        // Written down whether or not the episode is there: a page that was fetched to find out
        // has answered for the whole season, and the next question about this track is free.
        if (page.episodes.isNotEmpty()) {
            cacheLock.withLock {
                listed.getOrPut(animeId) { mutableMapOf() }[chosen.id] = page.episodes.map { it.number }.toSet()
            }
        }
        val wanted = page.episodes.firstOrNull { it.number == episode }
        if (page.episodes.isNotEmpty() && wanted == null) throw KodikError.NotFound(KodikError.Missing.EPISODE)
        val form = Parameters.build {
            append("d", page.domain); append("d_sign", page.dSign)
            append("pd", page.pd); append("pd_sign", page.pdSign)
            // Already decoded by the parser. Encoding it a second time is the classic way to
            // make Kodik answer "invalid signature".
            append("ref", page.ref); append("ref_sign", page.refSign)
            // A page with no episode list is a movie: its only video is the one it already shows.
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
        return KodikStream(
            urls = KodikLinkDecoder.decode(body),
            headers = mapOf("Referer" to url, "Origin" to PLAYER_HOST, "User-Agent" to BROWSER_UA),
            translation = chosen,
            // A movie is its own single episode however the caller numbered it.
            episode = if (page.episodes.isEmpty()) 1 else episode,
            season = season,
        )
    }

    private suspend fun catalogue(animeId: Int): KodikPlayerPage {
        require(animeId > 0) { "Anime id must be positive." }
        cacheLock.withLock { cached(animeId) }?.let { return it.page }
        val answer = getPlayer(animeId)
        val link = answer.string("link")
        if ((answer["found"] as? JsonPrimitive)?.booleanOrNull != true || link.isBlank()) {
            throw KodikError.NotFound(KodikError.Missing.TITLE)
        }
        val page = loadPage(playerUrl(link), "$PLAYER_HOST/").withSoleTrack()
        cacheLock.withLock {
            // What the old catalogue's tracks listed was read against media ids the new page may
            // no longer carry, so the lists go with the catalogue they were read under.
            drop(animeId)
            cache[animeId] = Catalogue(page, nowMillis())
        }
        return page
    }

    /**
     * A film with a single voice, given the one entry its page never drew.
     *
     * Kodik renders no translations box when there is nothing to choose between, so such a page
     * parses with an empty track list — and an empty list meant nothing to resolve, so every film
     * of that kind came back as «серия недоступна». The page still names the voice it is showing
     * in its own script, so the entry is made from the page rather than invented: the page's own
     * media id and hash, the id and title it gives itself, one episode because a film is one.
     *
     * A serial can never reach this: [KodikHtmlParser] refuses a serial page with no box.
     */
    private fun KodikPlayerPage.withSoleTrack(): KodikPlayerPage {
        if (translations.isNotEmpty()) return this
        return copy(translations = listOf(KodikTranslationOption(
            // The page's own translation id, which is a real Kodik id and so cannot collide with
            // one from a chooser. Only a page that names none falls back, and it falls back to a
            // negative number derived from the media id: stable for this film and impossible to
            // mistake for a track anything else remembers.
            id = currentTranslationId ?: -(currentId.toIntOrNull()?.takeIf { it > 0 } ?: 1),
            title = currentTranslationTitle ?: SOLE_TRACK_TITLE,
            type = TranslationType.VOICE, episodesCount = 1,
            mediaId = currentId, mediaHash = currentHash,
        )))
    }

    /** The catalogue still worth answering from, or null having dropped one that has aged out. Under [cacheLock]. */
    private fun cached(animeId: Int): Catalogue? {
        val held = cache[animeId] ?: return null
        // A negative age means the clock moved backwards; re-read rather than trust it.
        val age = nowMillis() - held.at
        if (age in 0 until catalogueTtlMillis) return held
        drop(animeId)
        return null
    }

    /** Everything remembered about one anime. Under [cacheLock]. */
    private fun drop(animeId: Int) {
        cache.remove(animeId)
        listed.remove(animeId)
    }

    /**
     * Kodik rejects a stale public token with `{"error": "Отсутствует или неверный токен"}`,
     * under HTTP 401 or under 200. Either way: re-obtain the token and try exactly once more.
     */
    private suspend fun getPlayer(animeId: Int): JsonObject {
        repeat(2) { attempt ->
            val token = token()
            val response = http.request("$API_URL/get-player") {
                browserHeaders("$PLAYER_HOST/")
                method = HttpMethod.Post
                setBody(FormDataContent(Parameters.build {
                    append("token", token.value); append("shikimoriID", animeId.toString())
                    append("types", "anime,anime-serial")
                }))
            }
            if (response.status == 401) {
                rejectToken(token, attempt)
            } else {
                val answer = try { wireJson.parseToJsonElement(response.successfulBody()).jsonObject }
                    catch (e: ApiException) { throw e }
                    catch (_: Exception) { throw KodikError.ParserBroken("get-player") }
                val error = answer.string("error")
                if (error.contains("токен", true) || error.contains("token", true)) {
                    rejectToken(token, attempt)
                } else return answer
            }
        }
        throw KodikError.NoToken()
    }

    private suspend fun token(): TokenState {
        // A key the app holds is read every time rather than remembered: a token typed into the
        // settings takes effect on the next request, not on the next launch.
        configuredToken()?.trim()?.takeIf { it.isNotEmpty() }?.let { return TokenState(configured = it) }
        tokens.load().takeIf { it.configured != null }?.let { return it }
        return tokenLock.withLock {
            var selected: TokenState? = null
            while (selected == null) {
                val state = tokens.load()
                if (state.configured != null || fresh(state.at)) {
                    selected = state
                    continue
                }
                val persisted = tokenCache.load()?.takeIf { fresh(it.storedAtMillis) }
                if (persisted != null) {
                    val restored = TokenState(automatic = persisted.token, at = persisted.storedAtMillis)
                    if (tokens.compareAndSet(state, restored)) selected = restored
                    continue
                }
                val response = http.request(ADD_PLAYERS_URL) { browserHeaders("$PLAYER_HOST/") }
                // A reset or override takes precedence over an older response, even a failed one.
                if (tokens.load() !== state) continue
                // A non-2xx here means no key, not a dead connection: the viewer must not be
                // told to check an internet connection that works.
                if (response.status !in 200..299) throw KodikError.NoToken()
                val scraped = KodikHtmlParser.extractPublicToken(response.body) ?: throw KodikError.NoToken()
                val now = nowMillis()
                tokenCache.store(scraped, now)
                val updated = TokenState(automatic = scraped, at = now)
                if (tokens.compareAndSet(state, updated)) selected = updated
            }
            selected
        }
    }

    private fun fresh(storedAt: Long?): Boolean {
        val age = nowMillis() - (storedAt ?: return false)
        return age in 0 until TOKEN_TTL_MILLIS
    }

    private suspend fun rejectToken(rejected: TokenState, attempt: Int) {
        // A late rejection cannot clear a newer configuration/cache snapshot — and the persisted
        // copy goes only with the memory it was restored into.
        if (rejected.configured == null && tokens.compareAndSet(rejected, TokenState())) tokenCache.clear()
        if (attempt == 1 || (rejected.configured != null && tokens.load() === rejected)) throw KodikError.NoToken()
    }

    private suspend fun loadPage(url: String, referer: String): KodikPlayerPage {
        val body = http.request(url) {
            browserHeaders(referer)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.successfulBody()
        return KodikHtmlParser.parse(body).copy(sourceUrl = url)
    }

    private fun playerUrl(link: String, season: Int? = null, episode: Int? = null): String {
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
        if (season != null) builder.parameters["season"] = season.toString()
        if (episode != null) builder.parameters["episode"] = episode.toString()
        return builder.buildString()
    }

    private fun HttpRequestBuilder.browserHeaders(referer: String) {
        header(HttpHeaders.UserAgent, BROWSER_UA)
        header(HttpHeaders.Referrer, referer)
    }

    companion object {
        /** The player host, which serves its pages only to something that looks like a desktop browser. */
        const val PLAYER_HOST = "https://kodikplayer.com"
        const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
        const val API_URL = "https://kodik-api.com"
        const val ADD_PLAYERS_URL = "https://kodik-add.com/add-players.min.js?v=2"

        /** For a film whose page names no studio: what the viewer is hearing, without a claim about who made it. */
        const val SOLE_TRACK_TITLE = "Единственная озвучка"

        const val CATALOGUE_TTL_MILLIS = 6L * 60 * 60 * 1000
        const val TOKEN_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
