package app.kaeru.domain.model

data class LibraryEntry(
    val anime: Anime,
    val rate: UserRate,
    val watch: WatchState?,
    /**
     * Where this device stopped inside each episode it has opened.
     *
     * Defaulted empty so a caller that only has the catalogue — a preview, a title outside the
     * list — can still build an entry. An entry with no rows behaves exactly as one whose episodes
     * were never started.
     */
    val progress: List<EpisodeProgress> = emptyList(),
) {
    /**
     * Episode to start from the watch control: an unfinished local episode that is newer than
     * Shikimori's progress, otherwise the episode after the recorded progress.
     */
    fun nextEpisode(watchedThreshold: Float): Int {
        val w = watch
        if (w != null && w.episode > rate.episodes) {
            return if (w.fraction < watchedThreshold) w.episode else w.episode + 1
        }
        return rate.episodes + 1
    }

    /** Progress within the current episode, or null when no episode is in progress. */
    fun progressFraction(watchedThreshold: Float): Float? {
        val w = watch ?: return null
        return if (w.episode == nextEpisode(watchedThreshold) && w.fraction in 0.01f..watchedThreshold) w.fraction else null
    }
}
