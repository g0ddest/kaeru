package app.kaeru.domain.model

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
        /**
         * Every episode of a finished show is behind the viewer: the title is done, as a title
         * turned «Завершено» is, and sync leaves a tombstone for it.
         */
        fun finished(anime: Anime, watched: Int, now: Instant): Boolean =
            anime.status == AnimeStatus.RELEASED &&
                anime.episodes > 0 &&
                watched >= anime.episodes &&
                anime.nextEpisodeAt?.isAfter(now) != true
    }
}
