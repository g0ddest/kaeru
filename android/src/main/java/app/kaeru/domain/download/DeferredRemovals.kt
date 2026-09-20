package app.kaeru.domain.download

/** One episode of one anime, which is all a promised deletion has to name. */
data class DownloadedEpisode(val animeId: Int, val episode: Int)

/**
 * The episodes «Удалять просмотренные» has promised to delete and has not deleted yet.
 *
 * The promise is made at the moment a mark is raised for an episode that is on the device, and it
 * is kept as soon as playback is off that episode. In between the process can die — the mark is
 * usually raised nine tenths of the way through an episode, with a viewer who may well close the
 * app on the credits — so the promise has to outlive it, or the space is never given back.
 *
 * Written down rather than worked out again on the next start, and that is the whole point of this
 * interface. «Every downloaded episode Shikimori has counted» looks like the same set and is not:
 * downloading an episode you have already seen is something the app deliberately offers — the
 * «Скачать…» sheet gives watched episodes their own block — and a start-up sweep that reasoned from
 * the count would delete a flight's worth of rewatching every time the app was opened, with no mark
 * and no playback anywhere in the story.
 */
interface DeferredRemovals {

    /** Everything promised and not yet done, including by a run that is over. */
    suspend fun pending(): Set<DownloadedEpisode>

    suspend fun record(episode: DownloadedEpisode)

    /** The episode is gone, or is not to be deleted after all. */
    suspend fun forget(episode: DownloadedEpisode)

    /** Every promise at once, for when the setting that made them is turned off. */
    suspend fun forgetAll()
}
