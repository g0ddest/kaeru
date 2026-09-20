package app.kaeru.data.local

import androidx.room.Entity
import app.kaeru.domain.model.EpisodeProgress
import java.time.Instant

/**
 * One position per anime and episode. Added in version 3, seeded from `watch_state` so nobody
 * loses the episode they were in the middle of when they upgrade.
 */
@Entity(tableName = "episode_progress", primaryKeys = ["animeId", "episode"])
data class EpisodeProgressEntity(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Instant,
) {
    fun toDomain() = EpisodeProgress(
        animeId = animeId,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )
}

fun EpisodeProgress.toEntity() = EpisodeProgressEntity(
    animeId = animeId,
    episode = episode,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
)
