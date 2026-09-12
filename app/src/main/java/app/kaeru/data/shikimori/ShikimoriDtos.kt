package app.kaeru.data.shikimori

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SHIKIMORI_BASE_URL = "https://shikimori.io/"

fun shikimoriJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class UserDto(
    val id: Long,
    val nickname: String,
    val avatar: String? = null,
)

@Serializable
data class ImageDto(
    val original: String? = null,
    val preview: String? = null,
)

@Serializable
data class ScreenshotDto(
    val original: String? = null,
    val preview: String? = null,
)

@Serializable
data class StudioDto(val name: String)

@Serializable
data class AnimeShortDto(
    val id: Int,
    val name: String = "",
    val russian: String? = null,
    val image: ImageDto? = null,
    val score: String? = null,
    val status: String = "released",
    val episodes: Int = 0,
    @SerialName("episodes_aired") val episodesAired: Int = 0,
    @SerialName("aired_on") val airedOn: String? = null,
)

@Serializable
data class AnimeDetailsDto(
    val id: Int,
    val name: String = "",
    val russian: String? = null,
    val image: ImageDto? = null,
    val score: String? = null,
    val status: String = "released",
    val episodes: Int = 0,
    @SerialName("episodes_aired") val episodesAired: Int = 0,
    @SerialName("aired_on") val airedOn: String? = null,
    val description: String? = null,
    @SerialName("next_episode_at") val nextEpisodeAt: String? = null,
    val studios: List<StudioDto> = emptyList(),
    val screenshots: List<ScreenshotDto> = emptyList(),
)

@Serializable
data class UserRateDto(
    val id: Long,
    @SerialName("target_id") val targetId: Int,
    val status: String,
    val episodes: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class UserRatePayload(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("target_id") val targetId: Int? = null,
    @SerialName("target_type") val targetType: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
)

/** Shikimori's REST `image` is a legacy field that returns a placeholder for newer titles; GraphQL has the real poster. */
@Serializable
data class GraphqlRequest(val query: String)

@Serializable
data class GraphqlPosterDto(@SerialName("mainUrl") val mainUrl: String? = null, @SerialName("originalUrl") val originalUrl: String? = null)

@Serializable
data class GraphqlAnimeDto(val id: String, val poster: GraphqlPosterDto? = null)

@Serializable
data class GraphqlAnimesData(val animes: List<GraphqlAnimeDto> = emptyList())

@Serializable
data class GraphqlAnimesResponse(val data: GraphqlAnimesData? = null)

const val MISSING_POSTER_MARKER = "missing_original"

fun isMissingPoster(url: String?): Boolean = url == null || url.contains(MISSING_POSTER_MARKER)

fun postersQuery(ids: List<Int>): GraphqlRequest =
    GraphqlRequest("{ animes(ids: \"${ids.joinToString(",")}\", limit: 50) { id poster { mainUrl originalUrl } } }")

@Serializable
data class UserRateRequest(
    @SerialName("user_rate") val userRate: UserRatePayload,
)
