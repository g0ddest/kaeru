package app.kaeru.domain.playback

/** The marks a test decides on, and a record of every episode they were asked about. */
class FakeSkipMarks(var answer: SkipMarks = SkipMarks.NONE) : SkipMarksSource {
    val asked = mutableListOf<String>()

    override suspend fun marks(animeId: Int, episode: Int, durationMs: Long): SkipMarks {
        asked += "$animeId/$episode@$durationMs"
        return answer
    }
}
