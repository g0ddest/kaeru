package app.kaeru.domain.playback

/**
 * Where an episode's opening and ending marks come from, as playback sees it: one question and
 * nothing about caches, networks or who marked them.
 *
 * The length is part of the question rather than a detail of the answer. Marks belong to one
 * file, and the same episode in another voice is another file; asking with the length the engine
 * reports is what keeps a button from appearing eight seconds off.
 */
interface SkipMarksSource {
    /**
     * The marks for this episode at this exact length, or [SkipMarks.NONE] when there are none.
     *
     * Never throws and never waits long: marks are a convenience, and an episode plays perfectly
     * well without them.
     */
    suspend fun marks(animeId: Int, episode: Int, durationMs: Long): SkipMarks
}
