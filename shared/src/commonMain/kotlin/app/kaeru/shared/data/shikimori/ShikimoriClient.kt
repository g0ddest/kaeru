package app.kaeru.shared.data.shikimori

import app.kaeru.shared.data.network.*
import app.kaeru.shared.domain.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

internal class ShikimoriClient(
    private val http: HttpTransport,
    private val clientId: String,
    private val proxyUrl: String,
    private val limiter: ShikimoriRateLimiter,
) {
    private val base = "https://shikimori.io"
    private val statuses = listOf("planned", "watching", "rewatching", "completed", "on_hold", "dropped")

    private suspend fun request(path: String, token: String = "", configure: HttpRequestBuilder.() -> Unit = {}): String {
        repeat(2) { attempt ->
            limiter.awaitSlot()
            val response = http.request("$base/$path") {
                header(HttpHeaders.UserAgent, "Kaeru/1.0")
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

    suspend fun search(query: String): List<Anime> = array(request("api/animes") {
        parameter("search", query); parameter("limit", 30)
    }).map(::anime).withRealPosters()

    suspend fun discover(): List<Anime> = catalogue { parameter("status", "ongoing") }

    suspend fun seasonal(year: Int, season: String): List<Anime> {
        require(year > 0 && season in listOf("winter", "spring", "summer", "autumn")) { "Invalid anime season." }
        return catalogue { parameter("season", "${season}_$year") }
    }

    private suspend fun catalogue(filters: HttpRequestBuilder.() -> Unit): List<Anime> =
        array(request("api/animes") {
            parameter("order", "popularity"); parameter("limit", 20); parameter("censored", "true")
            filters()
        }).map(::anime).distinctBy { it.id }.withRealPosters()

    suspend fun details(animeId: Int): Anime {
        require(animeId > 0) { "Anime id must be positive." }
        return listOf(anime(parseObject(request("api/animes/$animeId")))).withRealPosters().single()
    }

    suspend fun account(token: String): Account {
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        val user = parseObject(request("api/users/whoami", token))
        val id = user.long("id")
        check(id > 0) { "Invalid account response." }
        return Account(id, user.string("nickname"), imageUrl(user.string("avatar")))
    }

    suspend fun library(userId: Long, token: String): List<LibraryItem> {
        require(userId > 0) { "User id must be positive." }
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        val rates = mutableListOf<JsonObject>()
        for (status in statuses) {
            var page = 1
            do {
                val batch = array(request("api/v2/user_rates", token) {
                    parameter("target_type", "Anime"); parameter("user_id", userId)
                    parameter("status", status); parameter("page", page); parameter("limit", 1000)
                })
                rates += batch
                page++
            } while (batch.size == 1000)
        }
        val cards = mutableMapOf<Int, Anime>()
        rates.forEach { rate -> embedded(rate)?.let { card -> cards[card.id] = card } }
        val missing = rates.map(::targetId).distinct().filter { it !in cards }
        for (ids in missing.chunked(50)) {
            array(request("api/animes") {
                parameter("ids", ids.joinToString(",")); parameter("limit", 50)
            }).map(::anime).forEach { cards[it.id] = it }
        }
        val enriched = cards.values.toList().withRealPosters().associateBy { it.id }
        return rates.distinctBy { it.long("id") }.map { rate ->
            val card = enriched[targetId(rate)] ?: throw Exception("Anime details are missing from the library response.")
            rate(rate, card)
        }
    }

    suspend fun setRate(animeId: Int, userId: Long, rateId: Long, status: String, episodes: Int, token: String): LibraryItem {
        require(userId > 0 && rateId >= 0 && episodes >= 0) { "Invalid library update." }
        require(status in statuses) { "Unknown library status." }
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        // Fetch before the mutation so a failed card fetch cannot hide a successful write.
        val card = details(animeId)
        val payload = buildJsonObject {
            put("status", status); put("episodes", episodes)
            if (rateId == 0L) { put("user_id", userId); put("target_id", animeId); put("target_type", "Anime") }
        }
        val path = "api/v2/user_rates" + if (rateId == 0L) "" else "/$rateId"
        val response = request(path, token) {
            method = if (rateId == 0L) HttpMethod.Post else HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("user_rate", payload) }.toString())
        }
        return rate(parseObject(response), card)
    }

    suspend fun token(grant: String, value: String): String {
        val url = runCatching { Url(proxyUrl.trim().trimEnd('/') + "/oauth/token") }.getOrNull()
        require(proxyUrl.isNotBlank() && url != null && url.protocol.name in listOf("http", "https") && url.host.isNotBlank()) {
            "OAuth proxy is not configured."
        }
        require(clientId.isNotBlank() && value.isNotBlank()) { "OAuth credentials are missing." }
        val form = Parameters.build {
            append("grant_type", grant); append("client_id", clientId)
            if (grant == "authorization_code") { append("code", value); append("redirect_uri", "kaeru://oauth") }
            else append("refresh_token", value)
        }
        val response = http.request(url.toString()) {
            method = HttpMethod.Post
            header(HttpHeaders.UserAgent, "Kaeru/1.0")
            // ByteArrayContent gives both Darwin and OkHttp a known Content-Length for the proxy.
            setBody(FormDataContent(form))
        }
        if (response.status == 400 || response.status == 401) {
            val oauthError = runCatching {
                (wireJson.parseToJsonElement(response.body) as? JsonObject)?.get("error") as? JsonPrimitive
            }.getOrNull()
            // A fixed marker survives the NSError bridge without exposing the body or credentials.
            if (oauthError?.isString == true && oauthError.content == "invalid_grant") {
                throw Exception("OAuth failed (HTTP ${response.status}): invalid_grant.")
            }
        }
        val parsed = parseObject(response.successfulBody())
        check(parsed.string("access_token").isNotBlank() && parsed.string("refresh_token").isNotBlank()) {
            "Invalid OAuth token response."
        }
        return parsed.toString()
    }

    /** GraphQL has current artwork; a failed batch keeps its REST posters without losing the catalogue. */
    private suspend fun List<Anime>.withRealPosters(): List<Anime> {
        val posters = mutableMapOf<Int, String>()
        for (ids in map { it.id }.distinct().chunked(50)) {
            try {
                val response = parseObject(request("api/graphql") {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("query", "{ animes(ids: \"${ids.joinToString(",")}\", limit: 50) { id poster { mainUrl originalUrl } } }")
                    }.toString())
                })
                val entries = (response["data"] as? JsonObject)?.get("animes") as? JsonArray
                entries.orEmpty().forEach { entry ->
                    val dto = entry as? JsonObject ?: return@forEach
                    val id = dto.int("id").takeIf { it in ids } ?: return@forEach
                    val poster = dto["poster"] as? JsonObject ?: return@forEach
                    val url = poster.string("mainUrl").ifBlank { poster.string("originalUrl") }
                    if (url.isNotBlank()) posters[id] = imageUrl(url)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cosmetic enrichment must not hide a successfully loaded REST catalogue.
            }
        }
        return map { card -> posters[card.id]?.let { card.copy(poster = it) } ?: card }
    }

    private fun array(body: String): List<JsonObject> = try {
        wireJson.parseToJsonElement(body).jsonArray.map { it.jsonObject }
    } catch (_: Exception) { throw Exception("Invalid API list response.") }

    private fun imageUrl(value: String): String = when {
        value.isBlank() -> ""
        value.startsWith("//") -> "https:$value"
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> base + value
        else -> "$base/$value"
    }

    private fun anime(dto: JsonObject): Anime {
        val id = dto.int("id")
        check(id > 0) { "Invalid anime response." }
        val image = dto["image"] as? JsonObject
        val poster = dto["poster"] as? JsonObject
        return Anime(
            id = id,
            title = dto.string("russian").ifBlank { dto.string("name") },
            originalTitle = dto.string("name"),
            poster = imageUrl(poster?.string("originalUrl").orEmpty().ifBlank {
                image?.string("original").orEmpty().ifBlank { image?.string("preview").orEmpty() }
            }),
            description = dto.string("description"),
            episodes = dto.int("episodes").coerceAtLeast(0),
            episodesAired = dto.int("episodes_aired").coerceAtLeast(0),
            status = dto.string("status"), score = dto.string("score"),
            year = dto.string("aired_on").take(4), nextEpisodeAt = dto.string("next_episode_at"),
            kind = dto.string("kind").takeIf { it.isNotBlank() },
            studios = (dto["studios"] as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.string("name")?.takeIf { name -> name.isNotBlank() }
            },
        )
    }

    private fun embedded(dto: JsonObject): Anime? =
        ((dto["target"] as? JsonObject) ?: (dto["anime"] as? JsonObject))?.takeIf { it.int("id") > 0 }?.let(::anime)

    private fun targetId(dto: JsonObject): Int = dto.int("target_id").takeIf { it > 0 }
        ?: embedded(dto)?.id ?: throw Exception("Library rate has no anime id.")

    private fun rate(dto: JsonObject, card: Anime): LibraryItem {
        check(dto.long("id") > 0) { "Invalid library rate response." }
        return LibraryItem(
            dto.long("id"), card, dto.string("status"), dto.int("episodes").coerceAtLeast(0),
            updatedAt = dto.string("updated_at").takeIf { it.isNotBlank() },
        )
    }
}
