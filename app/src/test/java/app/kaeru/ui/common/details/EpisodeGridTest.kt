package app.kaeru.ui.common.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EpisodeGridTest {
    private val threshold = 0.9f

    private fun anime(
        episodes: Int,
        aired: Int,
        status: AnimeStatus = AnimeStatus.ONGOING,
    ) = Anime(
        id = 7,
        nameRu = "Фрирен",
        nameRomaji = "Frieren",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = null,
        score = 9.1,
        year = 2023,
        studio = "Madhouse",
        description = null,
    )

    private fun rate(episodes: Int, status: ListStatus = ListStatus.WATCHING) =
        UserRate(1, 7, status, episodes, Instant.EPOCH)

    private fun watch(episode: Int, positionMs: Long, durationMs: Long = 1_400_000) =
        WatchState(7, episode, positionMs, durationMs, translationId = 11, kodikSeason = 1, updatedAt = Instant.EPOCH)

    @Test
    fun `the grid runs to the announced length and stops being playable at what aired`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, threshold)
        assertEquals(28, cells.size)
        assertEquals((1..28).toList(), cells.map { it.number })
        assertTrue(cells.first { it.number == 24 }.aired)
        assertFalse(cells.first { it.number == 25 }.aired)
    }

    @Test
    fun `episodes Shikimori counted are watched and the rest are not`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, threshold)
        assertTrue(cells.first { it.number == 20 }.watched)
        assertFalse(cells.first { it.number == 21 }.watched)
    }

    @Test
    fun `an episode the viewer already watched counts as aired whatever the catalogue says`() {
        // Shikimori says 24 of 28 are out; the viewer has 26 behind them, so 26 plainly exists.
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(26), null, threshold)
        assertTrue(cells.first { it.number == 26 }.aired)
        assertFalse(cells.first { it.number == 27 }.aired)
    }

    @Test
    fun `an episode open on this device extends the grid past what the catalogue announced`() {
        val cells = episodeCells(anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), rate(12), watch(13, 60_000), threshold)
        assertEquals(13, cells.size)
        assertTrue(cells.last().aired)
    }

    @Test
    fun `progress shows on the episode in progress and nowhere else`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), watch(21, 700_000), threshold)
        assertEquals(0.5f, cells.first { it.number == 21 }.progress!!, 0.001f)
        assertNull(cells.first { it.number == 20 }.progress)
        assertNull(cells.first { it.number == 22 }.progress)
    }

    @Test
    fun `an episode watched past the threshold shows no strip because it is behind the viewer`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), watch(21, 1_350_000), threshold)
        assertNull(cells.first { it.number == 21 }.progress)
    }

    @Test
    fun `a position in an episode Shikimori already counted is spent and is not drawn`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(22), watch(21, 700_000), threshold)
        assertNull(cells.first { it.number == 21 }.progress)
    }

    @Test
    fun `an anime outside the list has a full grid with nothing watched`() {
        val cells = episodeCells(anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), null, null, threshold)
        assertEquals(12, cells.size)
        assertTrue(cells.all { it.aired })
        assertTrue(cells.none { it.watched })
        assertTrue(cells.all { it.progress == null })
    }

    @Test
    fun `an announcement with no episodes at all has no grid to draw`() {
        assertEquals(emptyList<EpisodeCell>(), episodeCells(anime(episodes = 0, aired = 0, AnimeStatus.ANONS), null, null, threshold))
    }

    @Test
    fun `a released season with no aired count still lists what it announced`() {
        val cells = episodeCells(anime(episodes = 12, aired = 0, AnimeStatus.RELEASED), null, null, threshold)
        assertEquals(12, cells.size)
        assertTrue(cells.all { it.aired })
    }
}
