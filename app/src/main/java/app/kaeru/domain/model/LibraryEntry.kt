package app.kaeru.domain.model

import app.kaeru.domain.playback.ContinueTarget

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
     * The per-episode positions, with the live pointer standing in for its own episode when the
     * table has no row for it.
     *
     * [watch] is the same position by another name — the one the player is writing right now — and
     * the two only disagree in the moment before the first sample of a session lands, or on a row
     * written before this table existed. Falling back to it there costs one object and means an
     * upgrade, or a write that half failed, never loses the episode in hand.
     */
    val episodeProgress: List<EpisodeProgress> = withLivePointer(anime.id, watch, progress)

    /**
     * Which episode the watch control starts, and where inside it. The one answer to «продолжить»,
     * shared by the home feed, the title screen and the player.
     */
    fun continueTarget(watchedThreshold: Float): ContinueTarget =
        ContinueTarget.of(rate, anime.availableEpisodes, anime.episodes, episodeProgress, watchedThreshold)

    /** Episode to start from the watch control. */
    fun nextEpisode(watchedThreshold: Float): Int = continueTarget(watchedThreshold).episode

    /** Progress within the episode being continued, or null when no episode is in progress. */
    fun progressFraction(watchedThreshold: Float): Float? {
        val target = continueTarget(watchedThreshold)
        if (target.positionMs <= 0) return null
        return episodeFraction(target.episode, watchedThreshold)
    }

    /**
     * How much of one episode to draw a strip over, or null when there is nothing worth drawing.
     *
     * Three ways an episode has no strip, and each is a different thing being said. One Shikimori
     * has counted is behind the viewer and already wears the check, so a strip would argue with
     * it. One watched past the threshold is finished, whatever the server has heard. And one that
     * was only opened — a mis-tap, ten seconds — was never really started, which is the whole
     * reason the position of the episode *before* it is still the one being offered.
     */
    fun episodeFraction(episode: Int, watchedThreshold: Float): Float? {
        if (episode <= rate.episodes) return null
        val row = progressAt(episode) ?: return null
        if (!row.started || !row.unfinished(watchedThreshold)) return null
        // A length nobody knows yet gives a fraction of zero, and a strip of zero width is an
        // invitation to wonder what it means. Nothing is drawn until there is something to draw.
        return row.fraction.takeIf { it >= 0.01f }
    }

    /** Where this device stopped inside one episode, or null if it never opened it. */
    fun progressAt(episode: Int): EpisodeProgress? = episodeProgress.firstOrNull { it.episode == episode }
}

private fun withLivePointer(
    animeId: Int,
    watch: WatchState?,
    progress: List<EpisodeProgress>,
): List<EpisodeProgress> {
    if (watch == null || progress.any { it.episode == watch.episode }) return progress
    return progress + EpisodeProgress(animeId, watch.episode, watch.positionMs, watch.durationMs, watch.updatedAt)
}
