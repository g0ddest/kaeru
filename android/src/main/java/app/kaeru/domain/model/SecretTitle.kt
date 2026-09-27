package app.kaeru.domain.model

import app.kaeru.shared.domain.sync.SecretRules
import java.time.Instant

/**
 * «Смотреть украдкой» for one title (spec 2026-09-26-kaeru-sync-design.md §2, §4): whether it is
 * on, how many episodes have been watched while it was, and when that last changed here.
 *
 * A row that is off is kept rather than deleted: its [at] is what a later change from another
 * device is compared with.
 */
data class SecretTitle(
    val animeId: Int,
    val on: Boolean,
    val watched: Int,
    val at: Instant,
) {
    companion object {
        /** [SecretRules.finished] for this app's card and clock. */
        fun finished(anime: Anime, watched: Int, now: Instant): Boolean = SecretRules.finished(
            released = anime.status == AnimeStatus.RELEASED,
            announcedEpisodes = anime.episodes,
            watched = watched,
            nextEpisodeAtMs = anime.nextEpisodeAt?.toEpochMilli(),
            nowMs = now.toEpochMilli(),
        )
    }
}
