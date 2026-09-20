package app.kaeru.shared.data.shikimori

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How Shikimori's REST answers are read and how a write to it is spelled.
 *
 * Lenient, because the catalogue is not strict about its own types — a score arrives quoted or
 * not — and coercing, because a field Shikimori sends as `null` is a field it does not have, not
 * a reason to drop the title. Defaults are left out of what is sent: a rate update names only the
 * half it changes, and Shikimori reads an absent field as «leave it».
 */
internal val restJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

@Serializable
data class UserDto(
    val id: Long,
    val nickname: String = "",
    val avatar: String? = null,
)

/** The legacy `image` field: a placeholder for anything added after the poster migration. */
@Serializable
data class ImageDto(
    val original: String? = null,
    val preview: String? = null,
)

/** The current poster field, where the REST answer carries one. GraphQL has it for everything. */
@Serializable
data class PosterDto(
    val originalUrl: String? = null,
    val mainUrl: String? = null,
)

@Serializable
data class ScreenshotDto(
    val original: String? = null,
    val preview: String? = null,
)

@Serializable
data class StudioDto(val name: String = "")

/**
 * One title, as the list and the details endpoints describe it. The list carries the card —
 * names, poster, status, counts, year; the details add the description, the studios, the next
 * episode and the screenshots. One type for both, with the detail fields simply empty on a card.
 */
@Serializable
data class AnimeDto(
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
    val poster: PosterDto? = null,
    val kind: String? = null,
) {
    /**
     * The poster REST offers, as an absolute URL: the current field where there is one, the
     * legacy image otherwise, its preview as the last resort. Null when it offers nothing.
     */
    val restPoster: String?
        get() = shikimoriUrl(poster?.originalUrl.orEmpty().ifBlank {
            image?.original.orEmpty().ifBlank { image?.preview.orEmpty() }
        })
}

/**
 * One row of a viewer's list. `target_id` names the anime on the v2 endpoint; an older shape
 * embeds the card as `target` or `anime` instead, so [animeId] reads whichever is there.
 */
@Serializable
data class UserRateDto(
    val id: Long,
    @SerialName("target_id") val targetId: Int = 0,
    val status: String = "",
    val episodes: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val target: AnimeDto? = null,
    val anime: AnimeDto? = null,
) {
    /** The card embedded in the row, if the endpoint embeds one. */
    val embedded: AnimeDto? get() = (target ?: anime)?.takeIf { it.id > 0 }

    val animeId: Int get() = targetId.takeIf { it > 0 } ?: embedded?.id ?: 0
}

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
internal data class UserRatePayload(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("target_id") val targetId: Int? = null,
    @SerialName("target_type") val targetType: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
)

@Serializable
internal data class UserRateRequest(@SerialName("user_rate") val userRate: UserRatePayload)

@Serializable
internal data class GraphqlRequest(val query: String)

/**
 * A Shikimori path or URL as something an image loader can fetch. Null for nothing at all:
 * an absent poster is absent, not the site's root.
 */
fun shikimoriUrl(value: String?): String? = when {
    value.isNullOrBlank() -> null
    value.startsWith("//") -> "https:$value"
    value.startsWith("https://") || value.startsWith("http://") -> value
    value.startsWith("/") -> ShikimoriClient.BASE_URL + value
    else -> "${ShikimoriClient.BASE_URL}/$value"
}
