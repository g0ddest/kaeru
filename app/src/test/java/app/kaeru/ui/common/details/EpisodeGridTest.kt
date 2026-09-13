package app.kaeru.ui.common.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
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

    private fun stopped(episode: Int, positionMs: Long, durationMs: Long = 1_400_000) =
        EpisodeProgress(7, episode, positionMs, durationMs, Instant.EPOCH)

    @Test
    fun `the grid runs to the announced length and stops being playable at what aired`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, emptyList(), threshold)
        assertEquals(28, cells.size)
        assertEquals((1..28).toList(), cells.map { it.number })
        assertTrue(cells.first { it.number == 24 }.aired)
        assertFalse(cells.first { it.number == 25 }.aired)
    }

    @Test
    fun `episodes Shikimori counted are watched and the rest are not`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, emptyList(), threshold)
        assertTrue(cells.first { it.number == 20 }.watched)
        assertFalse(cells.first { it.number == 21 }.watched)
    }

    @Test
    fun `an episode the viewer already watched counts as aired whatever the catalogue says`() {
        // Shikimori says 24 of 28 are out; the viewer has 26 behind them, so 26 plainly exists.
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(26), null, emptyList(), threshold)
        assertTrue(cells.first { it.number == 26 }.aired)
        assertFalse(cells.first { it.number == 27 }.aired)
    }

    @Test
    fun `an episode open on this device extends the grid past what the catalogue announced`() {
        val cells = episodeCells(anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), rate(12), watch(13, 60_000), emptyList(), threshold)
        assertEquals(13, cells.size)
        assertTrue(cells.last().aired)
    }

    @Test
    fun `progress shows on the episode in progress and nowhere else`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), watch(21, 700_000), emptyList(), threshold)
        assertEquals(0.5f, cells.first { it.number == 21 }.progress!!, 0.001f)
        assertNull(cells.first { it.number == 20 }.progress)
        assertNull(cells.first { it.number == 22 }.progress)
    }

    @Test
    fun `every episode with a position of its own carries its own strip`() {
        // The whole point of the table: two episodes half-watched at once, and the grid says so
        // about both rather than about whichever was opened last.
        val cells = episodeCells(
            anime(episodes = 28, aired = 24), rate(20), null,
            listOf(stopped(21, 700_000), stopped(23, 350_000)), threshold,
        )

        assertEquals(0.5f, cells.first { it.number == 21 }.progress!!, 0.001f)
        assertEquals(0.25f, cells.first { it.number == 23 }.progress!!, 0.001f)
        assertNull(cells.first { it.number == 22 }.progress)
    }

    @Test
    fun `ten seconds left by a mis-tap is not an episode the grid calls started`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, listOf(stopped(22, 10_000)), threshold)

        assertNull(cells.first { it.number == 22 }.progress)
    }

    @Test
    fun `an episode finished on this device shows no strip even before Shikimori counts it`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), null, listOf(stopped(21, 1_350_000)), threshold)

        assertNull(cells.first { it.number == 21 }.progress)
    }

    @Test
    fun `a row for an episode past the announced season stretches the grid to reach it`() {
        val cells = episodeCells(
            anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), rate(12), null,
            listOf(stopped(13, 700_000)), threshold,
        )

        assertEquals(13, cells.size)
        assertTrue(cells.last().aired)
    }

    @Test
    fun `a row nobody really started does not conjure an episode the season does not have`() {
        // A stale or mis-numbered row for a thirteenth episode would otherwise add a tile the grid
        // calls playable while drawing no strip on it — the two rules disagreeing in public.
        val cells = episodeCells(
            anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), rate(12), null,
            listOf(stopped(13, 10_000)), threshold,
        )

        assertEquals(12, cells.size)
    }

    @Test
    fun `an episode watched past the threshold shows no strip because it is behind the viewer`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(20), watch(21, 1_350_000), emptyList(), threshold)
        assertNull(cells.first { it.number == 21 }.progress)
    }

    @Test
    fun `a position in an episode Shikimori already counted is spent and is not drawn`() {
        val cells = episodeCells(anime(episodes = 28, aired = 24), rate(22), watch(21, 700_000), emptyList(), threshold)
        assertNull(cells.first { it.number == 21 }.progress)
    }

    @Test
    fun `an anime outside the list has a full grid with nothing watched`() {
        val cells = episodeCells(anime(episodes = 12, aired = 12, AnimeStatus.RELEASED), null, null, emptyList(), threshold)
        assertEquals(12, cells.size)
        assertTrue(cells.all { it.aired })
        assertTrue(cells.none { it.watched })
        assertTrue(cells.all { it.progress == null })
    }

    @Test
    fun `an announcement with no episodes at all has no grid to draw`() {
        assertEquals(emptyList<EpisodeCell>(), episodeCells(anime(episodes = 0, aired = 0, AnimeStatus.ANONS), null, null, emptyList(), threshold))
    }

    @Test
    fun `an ongoing season nobody has announced the length of runs to what is out`() {
        val cells = episodeCells(anime(episodes = 0, aired = 24), rate(20), null, emptyList(), threshold)
        assertEquals(24, cells.size)
        assertTrue(cells.all { it.aired })
        assertEquals(20, cells.count { it.watched })
    }

    @Test
    fun `an announcement lists the episodes it promised as still to come`() {
        // Twelve announced, none made: the grid shows the season without opening any of it.
        val cells = episodeCells(anime(episodes = 12, aired = 0, AnimeStatus.ANONS), null, null, emptyList(), threshold)
        assertEquals(12, cells.size)
        assertTrue(cells.none { it.aired })
    }

    @Test
    fun `a released season with no aired count still lists what it announced`() {
        val cells = episodeCells(anime(episodes = 12, aired = 0, AnimeStatus.RELEASED), null, null, emptyList(), threshold)
        assertEquals(12, cells.size)
        assertTrue(cells.all { it.aired })
    }
}
