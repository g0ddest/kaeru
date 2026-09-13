package app.kaeru.ui.tv

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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

    private fun stopped(episode: Int, positionMs: Long, durationMs: Long = 1_200_000) =
        EpisodeProgress(1, episode, positionMs, durationMs, Instant.EPOCH)

    private fun item(
        kind: FeedKind,
        episode: Int,
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 12,
        aired: Int = 8,
        id: Int = 1,
    ) = FeedItem(entry(anime(id, status, episodes, aired)), episode = episode, kind = kind)

    // --- the watch button ------------------------------------------------------------------

    @Test
    fun `an episode already started is continued, not started again`() {
        val action = tvWatchAction(item(FeedKind.CONTINUE, episode = 5))
        assertEquals(TvWatchAction.Play(5, "Продолжить 5 серию"), action)
    }

    @Test
    fun `an episode not yet started is watched`() {
        assertEquals(
            TvWatchAction.Play(6, "Смотреть 6 серию"),
            tvWatchAction(item(FeedKind.NEW_EPISODE, episode = 6)),
        )
        assertEquals(
            TvWatchAction.Play(1, "Смотреть 1 серию"),
            tvWatchAction(item(FeedKind.PLANNED, episode = 1, status = AnimeStatus.RELEASED)),
        )
    }

    @Test
    fun `an episode that has not aired is not offered`() {
        assertEquals(TvWatchAction.NotAired, tvWatchAction(item(FeedKind.UPCOMING, episode = 9)))
    }

    @Test
    fun `a show with nothing aired at all is not offered either`() {
        val announced = item(FeedKind.PLANNED, episode = 1, status = AnimeStatus.ANONS, episodes = 0, aired = 0)
        assertEquals(TvWatchAction.NotAired, tvWatchAction(announced))
    }

    @Test
    fun `an announcement is not offered even once it promises a season length`() {
        val announced = item(FeedKind.PLANNED, episode = 1, status = AnimeStatus.ANONS, episodes = 12, aired = 0)
        assertEquals(TvWatchAction.NotAired, tvWatchAction(announced))
    }

    @Test
    fun `a finished show counts its whole run as available`() {
        val last = item(FeedKind.NEXT_UP, episode = 12, status = AnimeStatus.RELEASED, episodes = 12, aired = 0)
        assertEquals(TvWatchAction.Play(12, "Смотреть 12 серию"), tvWatchAction(last))
    }

    // --- the episode grid ------------------------------------------------------------------

    @Test
    fun `the grid runs the whole announced season, not only what has aired`() {
        val grid = tvEpisodeGrid(entry())
        assertEquals((1..12).toList(), grid.map { it.episode })
        assertEquals(List(8) { true } + List(4) { false }, grid.map { it.aired })
    }

    @Test
    fun `episodes behind the viewer carry the check`() {
        val grid = tvEpisodeGrid(entry(watchedEpisodes = 4))
        assertEquals(listOf(1, 2, 3, 4), grid.filter { it.watched }.map { it.episode })
    }

    @Test
    fun `the episode being watched carries a progress strip and no other does`() {
        val watch = WatchState(1, episode = 5, positionMs = 300_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = tvEpisodeGrid(entry(watchedEpisodes = 4, watch = watch))
        assertEquals(0.25f, grid.single { it.episode == 5 }.progress)
        assertTrue(grid.filter { it.episode != 5 }.all { it.progress == null })
    }

    @Test
    fun `every episode left part-watched carries its own strip`() {
        val grid = tvEpisodeGrid(
            entry(watchedEpisodes = 4, progress = listOf(stopped(5, 300_000), stopped(7, 600_000))),
        )

        assertEquals(0.25f, grid.single { it.episode == 5 }.progress)
        assertEquals(0.5f, grid.single { it.episode == 7 }.progress)
        assertNull(grid.single { it.episode == 6 }.progress)
    }

    @Test
    fun `an episode watched to its end is no longer in progress`() {
        val watch = WatchState(1, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = tvEpisodeGrid(entry(watchedEpisodes = 4, watch = watch))
        assertNull(grid.single { it.episode == 5 }.progress)
    }

    @Test
    fun `a finished show has every episode playable`() {
        val grid = tvEpisodeGrid(entry(anime(status = AnimeStatus.RELEASED, episodes = 12, aired = 0)))
        assertEquals(12, grid.size)
        assertTrue(grid.all { it.aired })
    }

    @Test
    fun `a show nothing is known about yet has an empty grid rather than a wrong one`() {
        val grid = tvEpisodeGrid(entry(anime(status = AnimeStatus.ANONS, episodes = 0, aired = 0), watchedEpisodes = 0))
        assertTrue(grid.isEmpty())
    }

    @Test
    fun `progress past the end of the catalogue still gets a cell`() {
        // Shikimori says eight aired, the viewer is on the ninth: the grid follows the viewer.
        val watch = WatchState(1, episode = 14, positionMs = 60_000, durationMs = 1_200_000, null, null, Instant.EPOCH)
        val grid = tvEpisodeGrid(entry(anime(episodes = 12, aired = 8), watchedEpisodes = 13, watch = watch))
        assertEquals(14, grid.size)
        assertTrue("watched episodes are playable whatever the catalogue says", grid[12].aired)
    }

    // --- finding a title again after a restart ----------------------------------------------

    @Test
    fun `a saved anime id is found anywhere in the feed`() {
        val watching = item(FeedKind.CONTINUE, episode = 5, id = 7)
        val planned = item(FeedKind.PLANNED, episode = 1, id = 9)
        val feed = HomeFeed(watching, listOf(watching), emptyList(), emptyList(), emptyList(), listOf(planned))
        assertEquals(planned, tvFeedItem(feed, 9))
        assertEquals(watching, tvFeedItem(feed, 7))
    }

    @Test
    fun `an anime the feed no longer carries is simply absent`() {
        assertNull(tvFeedItem(HomeFeed.EMPTY, 7))
    }
}
