package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.kaeru.domain.model.WatchState
import java.time.Instant

@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val translationId: Int?,
    val kodikSeason: Int?,
    val updatedAt: Instant,
) {
    fun toDomain() = WatchState(
        animeId = animeId,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        translationId = translationId,
        kodikSeason = kodikSeason,
        updatedAt = updatedAt,
    )
}

fun WatchState.toEntity() = WatchStateEntity(
    animeId = animeId,
    episode = episode,
    positionMs = positionMs,
    durationMs = durationMs,
    translationId = translationId,
    kodikSeason = kodikSeason,
    updatedAt = updatedAt,
)
