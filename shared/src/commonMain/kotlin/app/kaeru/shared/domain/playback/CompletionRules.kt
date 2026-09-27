package app.kaeru.shared.domain.playback

/**
 * When an episode just counted as watched is worth offering «завершить» over.
 *
 * Only an offer: «watched the finale» and «finished the show» are the viewer's call.
 */
object CompletionRules {

    /**
     * The announced number of episodes is reached, and no next episode is on the schedule.
     *
     * The schedule matters because the announced count lags behind a show that got longer — twelve
     * announced, a thirteenth in four days. A date at or before [nowMs] schedules nothing. Times are
     * epoch milliseconds; zero [announcedEpisodes] means the length is unknown, which never offers.
     */
    fun offerCompletion(episode: Int, announcedEpisodes: Int, nextEpisodeAtMs: Long?, nowMs: Long): Boolean {
        val moreScheduled = nextEpisodeAtMs != null && nextEpisodeAtMs > nowMs
        return !moreScheduled && announcedEpisodes > 0 && episode >= announcedEpisodes
    }
}
