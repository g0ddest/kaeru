package app.kaeru.domain.playback

import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TranslationUsageTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")

    private fun row(animeId: Int, translationId: Int?) =
        WatchState(animeId, episode = 1, positionMs = 0, durationMs = 0, translationId, kodikSeason = null, updatedAt = now)

    @Test
    fun `each anime counts once for the track it was last played with`() {
        val states = listOf(
            row(animeId = 1, translationId = 11),
            row(animeId = 2, translationId = 11),
            row(animeId = 3, translationId = 22),
        )

        assertEquals(mapOf(11 to 2, 22 to 1), TranslationUsage.of(states))
    }

    @Test
    fun `an anime that never remembered a track counts for nothing`() {
        val states = listOf(row(animeId = 1, translationId = null), row(animeId = 2, translationId = 11))

        assertEquals(mapOf(11 to 1), TranslationUsage.of(states))
    }

    @Test
    fun `nothing watched yet means nothing counted`() {
        assertEquals(emptyMap<Int, Int>(), TranslationUsage.of(emptyList()))
    }

    @Test
    fun `a repeated anime still counts once, because the count is of anime and not of rows`() {
        val states = listOf(row(animeId = 1, translationId = 11), row(animeId = 1, translationId = 11))

        assertEquals(mapOf(11 to 1), TranslationUsage.of(states))
    }

    @Test
    fun `a track two anime carry is often chosen, one anime is not`() {
        val usage = TranslationUsage.of(
            listOf(row(1, translationId = 11), row(2, translationId = 11), row(3, translationId = 22)),
        )

        assertEquals(2, TranslationUsage.OFTEN_CHOSEN_FROM)
        assertEquals(listOf(true, false, false), listOf(11, 22, 33).map { TranslationUsage.oftenChosen(usage, it) })
    }
}
