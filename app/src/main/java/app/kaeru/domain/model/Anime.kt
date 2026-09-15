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

    /**
     * Number of episodes actually available to watch: aired so far for an ongoing show, nothing
     * at all for an announcement that has not started, and the announced total for a finished
     * show — falling back to what aired if the total itself is unknown.
     */
    val availableEpisodes: Int get() = when (status) {
        AnimeStatus.ONGOING -> episodesAired
        AnimeStatus.ANONS -> 0
        AnimeStatus.RELEASED -> if (episodes > 0) episodes else episodesAired
    }
}
