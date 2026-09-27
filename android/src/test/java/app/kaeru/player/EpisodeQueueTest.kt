package app.kaeru.player

import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Seeks, rungs and the next target. The end-of-episode rules are tested in `shared`. */
class EpisodeQueueTest {
    private val track = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12, season = 2)

    @Test
    fun `the next episode keeps the anime and the track and starts from the beginning`() {
        val current = PlaybackTarget(animeId = 100, episode = 4, startPositionMs = 640_000, translation = track)

        assertEquals(PlaybackTarget(100, 5, 0, track), EpisodeQueue.next(current))
    }

    @Test
    fun `seeking is clamped to the episode`() {
        assertEquals(0L, EpisodeQueue.clampSeek(-4_000, durationMs = 600_000))
        assertEquals(600_000L, EpisodeQueue.clampSeek(900_000, durationMs = 600_000))
        assertEquals(12_000L, EpisodeQueue.clampSeek(12_000, durationMs = 600_000))
    }

    @Test
    fun `seeking past an unknown duration is allowed forward but never before the start`() {
        assertEquals(0L, EpisodeQueue.clampSeek(-1, durationMs = 0))
        assertEquals(95_000L, EpisodeQueue.clampSeek(95_000, durationMs = 0))
    }

    @Test
    fun `the quality to start with is the remembered one when the stream carries it`() {
        val offered = setOf(Quality.P360, Quality.P480, Quality.P720)

        assertEquals(Quality.P480, EpisodeQueue.startQuality(offered, preferred = Quality.P480))
    }

    @Test
    fun `a remembered quality the stream does not carry falls back to the best on offer`() {
        val offered = setOf(Quality.P360, Quality.P480)

        assertEquals(Quality.P480, EpisodeQueue.startQuality(offered, preferred = Quality.P1080))
        assertEquals(Quality.P480, EpisodeQueue.startQuality(offered, preferred = null))
    }

    @Test
    fun `a stream with nothing on offer has no quality to start at`() {
        assertNull(EpisodeQueue.startQuality(emptySet(), preferred = Quality.P720))
    }
}
