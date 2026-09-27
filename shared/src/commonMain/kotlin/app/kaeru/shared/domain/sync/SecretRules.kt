package app.kaeru.shared.domain.sync

/**
 * «Смотреть украдкой» (spec 2026-09-26-kaeru-sync-design.md §4): a title nothing about which goes
 * to Shikimori, its progress counted here and synced as `secret.watched`.
 *
 * Only the decisions. Keeping the row, telling sync, and the one write to Shikimori are the
 * platform's. «Counted» is the episode count Shikimori's rate holds for the title; null when there
 * is no rate.
 */
object SecretRules {

    /**
     * Every episode of a released show is behind the viewer, and no next one is scheduled after
     * [nowMs]: the title is done, as a title turned «Завершено» is, and sync leaves a tombstone for
     * it. Zero [announcedEpisodes] is a length not known, which never finishes.
     */
    fun finished(released: Boolean, announcedEpisodes: Int, watched: Int, nextEpisodeAtMs: Long?, nowMs: Long): Boolean =
        released && announcedEpisodes > 0 && watched >= announcedEpisodes &&
            !(nextEpisodeAtMs != null && nextEpisodeAtMs > nowMs)

    /** Turned on: the count starts from Shikimori's, and the rate there is left exactly as it is. */
    fun watchedWhenTurnedOn(counted: Int?): Int = counted ?: 0

    /**
     * Turned off for another status: what was watched meanwhile goes to Shikimori once, and only
     * when it is more than the rate already says. Null sends nothing.
     */
    fun episodesToSendWhenTurnedOff(watched: Int, counted: Int?): Int? = watched.takeIf { it > (counted ?: 0) }

    /**
     * An episode marked or unmarked leaves the count at [episodes], as it would a rate's — never
     * below zero. Null when the count is already that, and nothing is written.
     */
    fun watchedAfterMark(watched: Int, episodes: Int): Int? =
        if (watched != episodes) episodes.coerceAtLeast(0) else null
}
