package app.kaeru.domain.download

/**
 * The promised deletions as a test can hold them.
 *
 * Deliberately outlives the [DeferredDownloadRemoval] that writes to it, so a test can build a
 * second one over the same set and have it stand for the next launch of the app.
 */
class FakeDeferredRemovals : DeferredRemovals {
    private val rows = linkedSetOf<DownloadedEpisode>()

    /** Seeds what a previous run is supposed to have promised. */
    fun seed(vararg episodes: DownloadedEpisode) = rows.addAll(episodes)

    override suspend fun pending(): Set<DownloadedEpisode> = rows.toSet()

    override suspend fun record(episode: DownloadedEpisode) {
        rows += episode
    }

    override suspend fun forget(episode: DownloadedEpisode) {
        rows -= episode
    }

    override suspend fun forgetAll() = rows.clear()
}
