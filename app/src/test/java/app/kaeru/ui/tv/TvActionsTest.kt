package app.kaeru.ui.tv

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val WATCHED_THRESHOLD = 0.9f

class TvActionsTest {

    private fun anime(
        id: Int = 1,
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 12,
        aired: Int = 8,
    ) = Anime(id, "Тайтл", "Title", null, emptyList(), status, episodes, aired, null, null, 2026, null, null)

    private fun entry(
        anime: Anime = anime(),
        watchedEpisodes: Int = 4,
        watch: WatchState? = null,
        progress: List<EpisodeProgress> = emptyList(),
    ) = LibraryEntry(
        anime,
        UserRate(anime.id.toLong(), anime.id, ListStatus.WATCHING, watchedEpisodes, Instant.EPOCH),
        watch,
        progress,
    )

    private fun grid(entry: LibraryEntry) = tvEpisodeGrid(entry.anime, entry, WATCHED_THRESHOLD)

    private fun stopped(episode: Int, positionMs: Long, durationMs: Long = 1_200_000) =
        EpisodeProgress(1, episode, positionMs, durationMs, Instant.EPOCH)

    // --- the episode grid ------------------------------------------------------------------

    @Test
    fun `the grid runs the whole announced season, not only what has aired`() {
        val grid = grid(entry())
        assertEquals((1..12).toList(), grid.map { it.episode })
        assertEquals(List(8) { true } + List(4) { false }, grid.map { it.aired })
    }

    @Test
    fun `episodes behind the viewer carry the check`() {
        val grid = grid(entry(watchedEpisodes = 4))
        assertEquals(listOf(1, 2, 3, 4), grid.filter { it.watched }.map { it.episode })
    }

    @Test
    fun `the episode being watched carries a progress strip and no other does`() {
        val watch = WatchState(1, episode = 5, positionMs = 300_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = grid(entry(watchedEpisodes = 4, watch = watch))
        assertEquals(0.25f, grid.single { it.episode == 5 }.progress)
        assertTrue(grid.filter { it.episode != 5 }.all { it.progress == null })
    }

    @Test
    fun `every episode left part-watched carries its own strip`() {
        val grid = grid(
            entry(watchedEpisodes = 4, progress = listOf(stopped(5, 300_000), stopped(7, 600_000))),
        )

        assertEquals(0.25f, grid.single { it.episode == 5 }.progress)
        assertEquals(0.5f, grid.single { it.episode == 7 }.progress)
        assertNull(grid.single { it.episode == 6 }.progress)
    }

    @Test
    fun `an episode watched to its end is no longer in progress`() {
        val watch = WatchState(1, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = grid(entry(watchedEpisodes = 4, watch = watch))
        assertNull(grid.single { it.episode == 5 }.progress)
    }

    @Test
    fun `a finished show has every episode playable`() {
        val grid = grid(entry(anime(status = AnimeStatus.RELEASED, episodes = 12, aired = 0)))
        assertEquals(12, grid.size)
        assertTrue(grid.all { it.aired })
    }

    @Test
    fun `a show nothing is known about yet has an empty grid rather than a wrong one`() {
        val grid = grid(entry(anime(status = AnimeStatus.ANONS, episodes = 0, aired = 0), watchedEpisodes = 0))
        assertTrue(grid.isEmpty())
    }

    @Test
    fun `progress past the end of the catalogue still gets a cell`() {
        // Shikimori says eight aired, the viewer is on the ninth: the grid follows the viewer.
        val watch = WatchState(1, episode = 14, positionMs = 60_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = grid(entry(anime(episodes = 12, aired = 8), watchedEpisodes = 13, watch = watch))
        assertEquals(14, grid.size)
        assertTrue("watched episodes are playable whatever the catalogue says", grid[12].aired)
    }

    /** The title screen also opens on an anime nobody has added, where everything is unwatched. */
    @Test
    fun `a title in no list has a full grid and nothing marked`() {
        val grid = tvEpisodeGrid(anime(), entry = null, watchedThreshold = WATCHED_THRESHOLD)
        assertEquals((1..12).toList(), grid.map { it.episode })
        assertTrue(grid.none { it.watched })
        assertTrue(grid.all { it.progress == null })
    }
}
