package app.kaeru.data.shikimori

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.shared.data.shikimori.AnimeDto
import app.kaeru.shared.data.shikimori.UserRateDto
import app.kaeru.shared.data.shikimori.shikimoriUrl
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The shared module's DTOs as this app's models: Room-shaped, with `java.time` and enums where
 * the wire has strings. iOS reads the same DTOs into its own shapes; the parsing happens once.
 */

internal fun parseStatus(status: String): AnimeStatus = when (status) {
    "ongoing" -> AnimeStatus.ONGOING
    "anons" -> AnimeStatus.ANONS
    else -> AnimeStatus.RELEASED
}

internal fun parseScore(score: String?): Double? =
    score?.toDoubleOrNull()?.takeIf { it > 0.0 }

internal fun parseYear(airedOn: String?): Int? =
    airedOn?.take(4)?.toIntOrNull()

internal fun parseInstant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
}

/**
 * One mapping for a list card and for the details: the fields the list does not carry arrive
 * empty and stay null, which is what `mergeShort` relies on to keep details fetched earlier.
 */
fun AnimeDto.toDomain(): Anime = Anime(
    id = id,
    nameRu = russian.orEmpty(),
    nameRomaji = name,
    posterUrl = restPoster,
    screenshotUrls = screenshots.mapNotNull { shikimoriUrl(it.original) },
    status = parseStatus(status),
    episodes = episodes,
    episodesAired = episodesAired,
    nextEpisodeAt = parseInstant(nextEpisodeAt),
    score = parseScore(score),
    year = parseYear(airedOn),
    studio = studios.firstOrNull()?.name,
    description = description?.replace(Regex("\\[/?[a-z_]+(=[^\\]]*)?]"), ""),
)

fun UserRateDto.toDomain(): UserRate = UserRate(
    id = id,
    animeId = animeId,
    status = ListStatus.fromApi(status),
    episodes = episodes,
    updatedAt = parseInstant(updatedAt) ?: Instant.EPOCH,
)
