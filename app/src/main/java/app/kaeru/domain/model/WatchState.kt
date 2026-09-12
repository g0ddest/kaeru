package app.kaeru.domain.model

import java.time.Instant

data class WatchState(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val translationId: Int?,
    val kodikSeason: Int?,
    val updatedAt: Instant,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
