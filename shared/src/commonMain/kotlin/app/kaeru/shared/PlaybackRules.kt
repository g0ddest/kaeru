package app.kaeru.shared

/** Pure playback decisions exported to Swift; no storage or player dependencies. */
object PlaybackRules {
    fun nextEpisode(watched: Int, aired: Int): Int =
        if (watched.coerceAtLeast(0) < aired) watched.coerceAtLeast(0) + 1 else 0

    fun shouldMarkWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs < 0) return false
        // ceil(duration * 9 / 10), without overflowing Long or rounding a large duration.
        val threshold = durationMs / 10 * 9 + (durationMs % 10 * 9 + 9) / 10
        return positionMs >= threshold
    }

    fun preferredTranslation(ids: List<Int>, remembered: Int): Int =
        remembered.takeIf { it in ids } ?: ids.firstOrNull() ?: 0
}
