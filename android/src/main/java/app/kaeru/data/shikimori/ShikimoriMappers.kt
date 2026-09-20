package app.kaeru.data.shikimori

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import java.time.Instant
import java.time.OffsetDateTime

internal fun absolute(path: String?): String? = path?.let {
    if (it.startsWith("http://") || it.startsWith("https://")) {
        it
    } else {
        SHIKIMORI_BASE_URL + it.removePrefix("/")
    }
}

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

fun AnimeShortDto.toDomain(): Anime = Anime(
    id = id,
    nameRu = russian.orEmpty(),
    nameRomaji = name,
    posterUrl = absolute(image?.original),
    screenshotUrls = emptyList(),
    status = parseStatus(status),
    episodes = episodes,
    episodesAired = episodesAired,
    nextEpisodeAt = null,
    score = parseScore(score),
    year = parseYear(airedOn),
    studio = null,
    description = null,
)

fun AnimeDetailsDto.toDomain(): Anime = Anime(
    id = id,
    nameRu = russian.orEmpty(),
    nameRomaji = name,
    posterUrl = absolute(image?.original),
    screenshotUrls = screenshots.mapNotNull { absolute(it.original) },
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
    animeId = targetId,
    status = ListStatus.fromApi(status),
    episodes = episodes,
    updatedAt = parseInstant(updatedAt) ?: Instant.EPOCH,
)
