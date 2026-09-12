package app.kaeru.player

import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeQueueTest {
    private val track = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12, season = 2)

    @Test
    fun `the next episode card appears half a minute before the end`() {
        val duration = 1_440_000L
        assertFalse(EpisodeQueue.nextEpisodeDue(duration - 30_001, duration, ended = false))
        assertTrue(EpisodeQueue.nextEpisodeDue(duration - 30_000, duration, ended = false))
    }

    @Test
    fun `an episode whose duration is not known yet never looks nearly over`() {
        assertFalse(EpisodeQueue.nextEpisodeDue(positionMs = 0, durationMs = 0, ended = false))
        assertFalse(EpisodeQueue.countdownDue(positionMs = 0, durationMs = 0, ended = false))
    }

    @Test
    fun `an episode that ended is over regardless of what the clock says`() {
        assertTrue(EpisodeQueue.nextEpisodeDue(positionMs = 0, durationMs = 0, ended = true))
        assertTrue(EpisodeQueue.countdownDue(positionMs = 0, durationMs = 0, ended = true))
    }

    @Test
    fun `the countdown starts only in the last ten seconds`() {
        val duration = 1_440_000L
        assertFalse(EpisodeQueue.countdownDue(duration - 10_001, duration, ended = false))
        assertTrue(EpisodeQueue.countdownDue(duration - 10_000, duration, ended = false))
    }

    @Test
    fun `the watched threshold is a fraction of the whole episode`() {
        assertFalse(EpisodeQueue.watched(positionMs = 899_999, durationMs = 1_000_000, threshold = 0.9f))
        assertTrue(EpisodeQueue.watched(positionMs = 900_000, durationMs = 1_000_000, threshold = 0.9f))
    }

    @Test
    fun `nothing counts as watched while the duration is unknown`() {
        assertFalse(EpisodeQueue.watched(positionMs = 5_000, durationMs = 0, threshold = 0.9f))
    }

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
