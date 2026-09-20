package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import java.time.Instant

@Entity(tableName = "anime")
data class AnimeEntity(
    @PrimaryKey val id: Int,
    val nameRu: String,
    val nameRomaji: String,
    val posterUrl: String?,
    val screenshots: List<String>,
    val status: AnimeStatus,
    val episodes: Int,
    val episodesAired: Int,
    val nextEpisodeAt: Instant?,
    val score: Double?,
    val year: Int?,
    val studio: String?,
    val description: String?,
    /** The last successful /api/animes/{id} fetch; null means this is only a short card. */
    val detailsFetchedAt: Instant?,
) {
    fun toDomain() = Anime(
        id = id,
        nameRu = nameRu,
        nameRomaji = nameRomaji,
        posterUrl = posterUrl,
        screenshotUrls = screenshots,
        status = status,
        episodes = episodes,
        episodesAired = episodesAired,
        nextEpisodeAt = nextEpisodeAt,
        score = score,
        year = year,
        studio = studio,
        description = description,
    )
}

fun Anime.toEntity(detailsFetchedAt: Instant?) = AnimeEntity(
    id = id,
    nameRu = nameRu,
    nameRomaji = nameRomaji,
    posterUrl = posterUrl,
    screenshots = screenshotUrls,
    status = status,
    episodes = episodes,
    episodesAired = episodesAired,
    nextEpisodeAt = nextEpisodeAt,
    score = score,
    year = year,
    studio = studio,
    description = description,
    detailsFetchedAt = detailsFetchedAt,
)

/** A short list response must not erase details fetched earlier. */
fun AnimeEntity.mergeShort(fresh: Anime): AnimeEntity = copy(
    nameRu = fresh.nameRu,
    nameRomaji = fresh.nameRomaji,
    posterUrl = fresh.posterUrl ?: posterUrl,
    status = fresh.status,
    episodes = fresh.episodes,
    episodesAired = fresh.episodesAired,
    score = fresh.score ?: score,
    year = fresh.year ?: year,
)
