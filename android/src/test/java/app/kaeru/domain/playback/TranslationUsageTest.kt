package app.kaeru.domain.playback

import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The watch_state rows through the shared count, which is tested in `shared`. */
class TranslationUsageTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")

    private fun row(animeId: Int, translationId: Int?) =
        WatchState(animeId, episode = 1, positionMs = 0, durationMs = 0, translationId, kodikSeason = null, updatedAt = now)

    @Test
    fun `rows are counted per anime by the track they remember`() {
        val usage = TranslationUsage.of(listOf(row(1, 11), row(2, 11), row(3, 22), row(4, null), row(1, 33)))

        assertEquals(mapOf(11 to 2, 22 to 1), usage)
        assertEquals(listOf(true, false), listOf(11, 22).map { TranslationUsage.oftenChosen(usage, it) })
    }
}
