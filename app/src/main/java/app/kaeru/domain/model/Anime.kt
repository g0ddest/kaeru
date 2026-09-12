package app.kaeru.domain.model

import java.time.Instant

enum class AnimeStatus { ONGOING, RELEASED, ANONS }

data class Anime(
    val id: Int,
    val nameRu: String,
    val nameRomaji: String,
    val posterUrl: String?,
    val screenshotUrls: List<String>,
    val status: AnimeStatus,
    val episodes: Int,
    val episodesAired: Int,
    val nextEpisodeAt: Instant?,
    val score: Double?,
    val year: Int?,
    val studio: String?,
    val description: String?,
) {
    val title: String get() = nameRu.ifBlank { nameRomaji }
    /** Number of episodes actually available: aired for ongoing shows, otherwise total. */
    val availableEpisodes: Int get() = if (status == AnimeStatus.ONGOING) episodesAired else if (episodes > 0) episodes else episodesAired
}
