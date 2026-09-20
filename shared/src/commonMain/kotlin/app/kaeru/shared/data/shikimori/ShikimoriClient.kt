package app.kaeru.shared.data.shikimori

import app.kaeru.shared.ApiException
import app.kaeru.shared.data.network.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Shikimori, one endpoint per method, with the token handed in per call.
 *
 * Every request goes through one process-wide rate limiter and repeats once after a 429, waiting
 * as long as `Retry-After` says. Nothing here holds a session: Swift keeps its own, Android keeps
 * its own, and both give this client the bearer to use — which is what lets a sign-in verify a
 * candidate identity with a token that is not yet the account's.
 *
 * Posters come from GraphQL, in [posters]: REST's `image` is a legacy field that answers with a
 * placeholder for anything added after the poster migration.
 */
class ShikimoriClient(
    private val http: HttpTransport,
    private val clientId: String,
    proxyUrl: String,
    private val limiter: ShikimoriRateLimiter = ShikimoriRateLimiter.shared,
    private val userAgent: String = "Kaeru/1.0",
    private val base: String = BASE_URL,
) {
    /**
     * The proxy's token endpoint, or null for a build assembled without a usable address. Nothing
     * is sent there without one: the secret an exchange needs is the worker's, and a code sent to
     * Shikimori directly would only earn an `invalid_client` and be burnt.
     */
    private val tokenEndpoint: String? = proxyUrl.trim().trimEnd('/')
        // Spelled out: an address with no scheme is a host to Ktor's parser, and a bare
        // `kaeru-relay.workers.dev` copied down from `TOGETHER_RELAY_URL` must not be dialled over
        // plain http with a code in the body.
        .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        ?.let { runCatching { Url("$it/oauth/token") }.getOrNull() }
        ?.takeIf { it.host.isNotBlank() }
        ?.toString()

    /** Whether this build can sign anyone in at all. */
    val oauthConfigured: Boolean get() = tokenEndpoint != null

    private suspend fun request(path: String, token: String = "", configure: HttpRequestBuilder.() -> Unit = {}): String {
        repeat(2) { attempt ->
            limiter.awaitSlot()
            val response = http.request("$base/$path") {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json")
                if (token.isNotBlank()) bearerAuth(token)
                configure()
            }
            if (response.status == 429 && attempt == 0) {
                delay((response.retryAfter?.toLongOrNull() ?: 1L).coerceIn(1, 60) * 1000)
            } else return response.successfulBody()
        }
        error("Unreachable")
    }

    /**
     * Who [token] belongs to. Sent without a bearer when it is blank, and answered 401 for it.
     *
     * The catalogue endpoints below take a token too, though they answer without one: Android
     * sends its session on every call, as it always has, and a wire that changes only because the
     * code moved is a wire nobody asked for. Swift asks them anonymously.
     */
    suspend fun whoami(token: String): UserDto = decode(request("api/users/whoami", token))

    /** Every row of the viewer's list, all six statuses, a thousand rows a page. */
    suspend fun libraryRates(userId: Long, token: String): List<UserRateDto> {
        val rates = mutableListOf<UserRateDto>()
        for (status in STATUSES) {
            var page = 1
            do {
                val batch = decodeList<UserRateDto>(request("api/v2/user_rates", token) {
                    parameter("target_type", "Anime"); parameter("user_id", userId)
                    parameter("status", status); parameter("page", page); parameter("limit", RATES_PAGE)
                })
                rates += batch
                page++
            } while (batch.size == RATES_PAGE)
        }
        return rates
    }

    /** The cards for [ids], fetched fifty at a time — Shikimori's ceiling for one `ids=` batch. */
    suspend fun animesByIds(ids: List<Int>, token: String = ""): List<AnimeDto> = ids.distinct().chunked(BATCH).flatMap { batch ->
        decodeList<AnimeDto>(request("api/animes", token) { parameter("ids", batch.joinToString(",")); parameter("limit", BATCH) })
    }

    /**
     * The catalogue itself rather than one viewer's list: what is airing now (`status`), or what a
     * season held (`season`). Most popular first, enough to fill a row, and `censored=true` so a
     * home screen is not where adult titles turn up.
     */
    suspend fun catalogue(status: String? = null, season: String? = null, token: String = ""): List<AnimeDto> =
        decodeList(request("api/animes", token) {
            parameter("order", "popularity"); parameter("limit", ROW); parameter("censored", "true")
            if (status != null) parameter("status", status)
            if (season != null) parameter("season", season)
        })

    suspend fun anime(id: Int, token: String = ""): AnimeDto = decode(request("api/animes/$id", token))

    suspend fun screenshots(id: Int, token: String = ""): List<ScreenshotDto> =
        decodeList(request("api/animes/$id/screenshots", token))

    suspend fun search(query: String, token: String = ""): List<AnimeDto> =
        decodeList(request("api/animes", token) { parameter("search", query); parameter("limit", 30) })

    /**
     * The real posters of [ids], by id, from GraphQL — `originalUrl` first, because `mainUrl` is
     * Shikimori's 225×318 thumbnail, which a card on a phone is already wider than and a hero
     * stretched fourfold; the thumbnail stays as the fallback, because a small poster beats none.
     *
     * Posters are cosmetic, and this runs on the way to a screen that has already loaded: a batch
     * that fails is simply missing from the answer, so a caller keeps whatever REST gave it rather
     * than losing the row. Cancellation is not a failed batch and is rethrown.
     */
    suspend fun posters(ids: List<Int>, token: String = ""): Map<Int, String> {
        val posters = mutableMapOf<Int, String>()
        for (batch in ids.distinct().chunked(BATCH)) {
            try {
                val query = "{ animes(ids: \"${batch.joinToString(",")}\", limit: $BATCH) { id poster { mainUrl originalUrl } } }"
                val response = parseObject(request("api/graphql", token) {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(restJson.encodeToString(GraphqlRequest.serializer(), GraphqlRequest(query)))
                })
                val entries = (response["data"] as? JsonObject)?.get("animes") as? JsonArray
                entries.orEmpty().forEach { entry ->
                    val dto = entry as? JsonObject ?: return@forEach
                    val id = dto.int("id").takeIf { it in batch } ?: return@forEach
                    val poster = dto["poster"] as? JsonObject ?: return@forEach
                    shikimoriUrl(poster.string("originalUrl").ifBlank { poster.string("mainUrl") })?.let { posters[id] = it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One bad batch costs one batch of posters, never the catalogue it decorates.
            }
        }
        return posters
    }

    suspend fun createUserRate(userId: Long, animeId: Int, status: String, episodes: Int? = null, token: String): UserRateDto =
        write("api/v2/user_rates", HttpMethod.Post, token, UserRatePayload(userId, animeId, "Anime", status, episodes))

    /** Names only the half it changes: Shikimori reads an absent field as «leave it». */
    suspend fun updateUserRate(rateId: Long, status: String? = null, episodes: Int? = null, token: String): UserRateDto =
        write("api/v2/user_rates/$rateId", HttpMethod.Patch, token, UserRatePayload(status = status, episodes = episodes))

    private suspend fun write(path: String, verb: HttpMethod, token: String, payload: UserRatePayload): UserRateDto =
        decode(request(path, token) {
            method = verb
            contentType(ContentType.Application.Json)
            setBody(restJson.encodeToString(UserRateRequest.serializer(), UserRateRequest(payload)))
        })

    /**
     * A code or a refresh token for a session, through Kaeru's proxy, which adds the client
     * secret on the way.
     *
     * A 400 or 401 whose body names `invalid_grant` comes back as an [ApiException] carrying that
     * one marker and nothing else: it is the single OAuth failure that means «sign in again», and
     * the body beside it can carry credentials that must not reach a crash report.
     */
    suspend fun token(grant: String, value: String, redirectUri: String = MOBILE_REDIRECT): TokenResponseDto {
        val url = requireNotNull(tokenEndpoint) { "OAuth proxy is not configured." }
        require(clientId.isNotBlank() && value.isNotBlank()) { "OAuth credentials are missing." }
        val form = Parameters.build {
            append("grant_type", grant); append("client_id", clientId)
            if (grant == "authorization_code") { append("code", value); append("redirect_uri", redirectUri) }
            else append("refresh_token", value)
        }
        val response = http.request(url) {
            method = HttpMethod.Post
            header(HttpHeaders.UserAgent, userAgent)
            // ByteArrayContent gives both Darwin and OkHttp a known Content-Length for the proxy.
            setBody(FormDataContent(form))
        }
        if (response.status == 400 || response.status == 401) {
            val oauthError = runCatching {
                (wireJson.parseToJsonElement(response.body) as? JsonObject)?.get("error") as? JsonPrimitive
            }.getOrNull()
            if (oauthError?.isString == true && oauthError.content == "invalid_grant") {
                throw ApiException(response.status, oauthError = "invalid_grant")
            }
        }
        val body = response.successfulBody()
        val tokens = runCatching { restJson.decodeFromString(TokenResponseDto.serializer(), body) }.getOrNull()
        check(tokens != null && tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank()) {
            "Invalid OAuth token response."
        }
        return tokens
    }

    private inline fun <reified T> decode(body: String): T = try {
        restJson.decodeFromString<T>(body)
    } catch (_: Exception) { throw Exception("Invalid API response.") }

    private inline fun <reified T> decodeList(body: String): List<T> = try {
        restJson.decodeFromString<List<T>>(body)
    } catch (_: Exception) { throw Exception("Invalid API list response.") }

    companion object {
        const val BASE_URL = "https://shikimori.io"
        const val MOBILE_REDIRECT = "kaeru://oauth"

        /** The six lists a viewer keeps, in the order the library is read. */
        val STATUSES = listOf("planned", "watching", "rewatching", "completed", "on_hold", "dropped")

        /** Shikimori's own ceiling for one `animes` GraphQL query, and for one `ids=` REST batch. */
        const val BATCH = 50
        private const val RATES_PAGE = 1000

        /** Enough to fill a row and a good scroll past it, and one request against the rate limit. */
        private const val ROW = 20
    }
}
