package app.kaeru.ui.common.home

import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val THRESHOLD = 0.9f

class HomeRowsTest {
    private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun anime(
        id: Int,
        episodes: Int = 12,
        aired: Int = 8,
        nextAt: Instant? = null,
        status: AnimeStatus = AnimeStatus.ONGOING,
    ) = Anime(
        id = id,
        nameRu = "Аниме $id",
        nameRomaji = "Anime $id",
        posterUrl = "https://poster/$id.jpg",
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = nextAt,
        score = 8.0,
        year = 2026,
        studio = "Madhouse",
        description = null,
    )

    private fun entry(
        id: Int,
        watched: Int = 6,
        watch: WatchState? = null,
        episodes: Int = 12,
        aired: Int = 8,
        nextAt: Instant? = null,
        status: AnimeStatus = AnimeStatus.ONGOING,
        progress: List<EpisodeProgress> = emptyList(),
    ) = LibraryEntry(
        anime = anime(id, episodes, aired, nextAt, status),
        rate = UserRate(id.toLong(), id, ListStatus.WATCHING, watched, now),
        watch = watch,
        progress = progress,
    )

    private fun watch(id: Int, episode: Int, positionMs: Long, durationMs: Long) =
        WatchState(id, episode, positionMs, durationMs, null, null, now)

    private fun stopped(id: Int, episode: Int, positionMs: Long, durationMs: Long = 1_440_000) =
        EpisodeProgress(id, episode, positionMs, durationMs, now)

    private fun item(entry: LibraryEntry, episode: Int, kind: FeedKind) = FeedItem(entry, episode, kind)

    private fun feed(
        top: FeedItem? = null,
        continueWatching: List<FeedItem> = emptyList(),
        newEpisodes: List<FeedItem> = emptyList(),
        nextUp: List<FeedItem> = emptyList(),
        upcoming: List<FeedItem> = emptyList(),
        planned: List<FeedItem> = emptyList(),
    ) = HomeFeed(top, continueWatching, newEpisodes, nextUp, upcoming, planned)

    private fun rows(feed: HomeFeed) = homeRows(feed, THRESHOLD, now, zone)

    @Test
    fun `rows keep their order and empty ones never appear`() {
        val newEpisode = item(entry(1), 7, FeedKind.NEW_EPISODE)
        val continuing = item(entry(2, watch = watch(2, 7, 60_000, 1_440_000)), 7, FeedKind.CONTINUE)
        val next = item(entry(3, status = AnimeStatus.RELEASED), 7, FeedKind.NEXT_UP)
        val soon = item(entry(4, nextAt = now.plus(Duration.ofDays(1))), 9, FeedKind.UPCOMING)
        val planned = item(entry(5), 1, FeedKind.PLANNED)

        val titles = rows(
            feed(
                top = continuing,
                continueWatching = listOf(continuing),
                newEpisodes = listOf(newEpisode),
                nextUp = listOf(next),
                upcoming = listOf(soon),
                planned = listOf(planned),
            ),
        ).map { it.title }

        assertEquals(
            listOf("Новые серии", "Продолжить", "Дальше по списку", "Скоро", "В планах"),
            titles,
        )
    }

    @Test
    fun `only the rows with items in them are built`() {
        val planned = item(entry(5), 1, FeedKind.PLANNED)
        assertEquals(listOf("В планах"), rows(feed(planned = listOf(planned))).map { it.title })
    }

    @Test
    fun `a new episode card names the episode and says nothing else`() {
        val card = rows(feed(newEpisodes = listOf(item(entry(1), 7, FeedKind.NEW_EPISODE)))).single().items.single()
        assertEquals("7 серия", card.badge)
        assertNull(card.subtitle)
        assertNull(card.progress)
    }

    @Test
    fun `a card carries the title poster and id of its anime`() {
        val card = rows(feed(newEpisodes = listOf(item(entry(1), 7, FeedKind.NEW_EPISODE)))).single().items.single()
        assertEquals(1, card.animeId)
        assertEquals("Аниме 1", card.title)
        assertEquals("https://poster/1.jpg", card.posterUrl)
    }

    @Test
    fun `a continued card shows how far in it is and how much is left`() {
        val entry = entry(2, watch = watch(2, 7, 600_000, 1_440_000))
        val card = rows(feed(continueWatching = listOf(item(entry, 7, FeedKind.CONTINUE)))).single().items.single()
        assertEquals("7 серия", card.badge)
        assertEquals("осталось 14 мин", card.subtitle)
        assertEquals(600_000f / 1_440_000f, card.progress!!, 0.001f)
    }

    @Test
    fun `the strip and the time left describe the badged episode, not the one opened last`() {
        // Ten seconds of the sixth by mistake, ten minutes of the seventh. The card is about the
        // seventh, and so is every word and pixel on it.
        val entry = entry(
            2,
            watch = watch(2, 6, 10_000, 1_440_000),
            progress = listOf(stopped(2, 7, 600_000), stopped(2, 6, 10_000)),
        )

        val card = rows(feed(continueWatching = listOf(item(entry, 7, FeedKind.CONTINUE)))).single().items.single()

        assertEquals("7 серия", card.badge)
        assertEquals("осталось 14 мин", card.subtitle)
        assertEquals(600_000f / 1_440_000f, card.progress!!, 0.001f)
    }

    @Test
    fun `an episode past the threshold keeps its strip honest by dropping it`() {
        val entry = entry(2, watch = watch(2, 7, 1_400_000, 1_440_000))
        val card = rows(feed(continueWatching = listOf(item(entry, 7, FeedKind.CONTINUE)))).single().items.single()
        assertNull(card.progress)
    }

    @Test
    fun `a continued card with no known length promises no time`() {
        val entry = entry(2, watch = watch(2, 7, 600_000, 0))
        val card = rows(feed(continueWatching = listOf(item(entry, 7, FeedKind.CONTINUE)))).single().items.single()
        assertEquals("7 серия", card.badge)
        assertNull(card.subtitle)
    }

    @Test
    fun `an upcoming card says which day the episode arrives`() {
        val entry = entry(4, nextAt = now.plus(Duration.ofHours(6)))
        val card = rows(feed(upcoming = listOf(item(entry, 9, FeedKind.UPCOMING)))).single().items.single()
        assertEquals("9 серия", card.badge)
        assertEquals("завтра", card.subtitle)
    }

    @Test
    fun `an upcoming card with no date still says something`() {
        val card = rows(feed(upcoming = listOf(item(entry(4), 9, FeedKind.UPCOMING)))).single().items.single()
        assertEquals("скоро", card.subtitle)
    }

    @Test
    fun `a planned card offers the season length instead of an episode number`() {
        val card = rows(feed(planned = listOf(item(entry(5, episodes = 24), 1, FeedKind.PLANNED)))).single().items.single()
        assertNull(card.badge)
        assertEquals("24 серии", card.subtitle)
    }

    @Test
    fun `a planned card with an unknown season length says nothing about it`() {
        val entry = entry(5, episodes = 0, aired = 0)
        val card = rows(feed(planned = listOf(item(entry, 1, FeedKind.PLANNED)))).single().items.single()
        assertNull(card.subtitle)
    }

    @Test
    fun `no card joins facts with a middle dot`() {
        val continuing = item(entry(2, watch = watch(2, 7, 600_000, 1_440_000)), 7, FeedKind.CONTINUE)
        val soon = item(entry(4, nextAt = now.plus(Duration.ofDays(3))), 9, FeedKind.UPCOMING)
        val planned = item(entry(5, episodes = 24), 1, FeedKind.PLANNED)
        val all = rows(
            feed(
                continueWatching = listOf(continuing),
                newEpisodes = listOf(item(entry(1), 7, FeedKind.NEW_EPISODE)),
                nextUp = listOf(item(entry(3, status = AnimeStatus.RELEASED), 7, FeedKind.NEXT_UP)),
                upcoming = listOf(soon),
                planned = listOf(planned),
            ),
        ).flatMap { row -> row.items.flatMap { listOfNotNull(it.badge, it.subtitle) } + row.title }
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue("«$it» joins facts with a middle dot", !it.contains("·")) }
    }

    // --- discovery ----------------------------------------------------------------------------

    private val summer = Season(SeasonKind.SUMMER, 2026)

    private fun catalogue(
        id: Int,
        episodes: Int = 12,
        aired: Int = 8,
        status: AnimeStatus = AnimeStatus.ONGOING,
        studio: String? = "Madhouse",
    ) = anime(id, episodes, aired, null, status).copy(studio = studio)

    private fun discover(
        popularNow: List<Anime>? = null,
        seasonal: List<Anime>? = null,
        loadingNow: Boolean = false,
        loadingSeasonal: Boolean = false,
        anySeasonLoaded: Boolean = false,
    ) = discoverRows(
        DiscoverUiState(summer, popularNow, seasonal, loadingNow, loadingSeasonal, anySeasonLoaded),
    )

    private fun cardsOf(row: DiscoverRow?) = (row?.content as DiscoverContent.Titles).cards

    // --- «Популярно сейчас» ---------------------------------------------------------------------

    @Test
    fun `the popular row is titled for what is airing rather than for the catalogue`() {
        val row = discover(popularNow = listOf(catalogue(1))).popularNow!!
        assertEquals("Популярно сейчас", row.title)
        assertEquals(listOf(1), cardsOf(row).map { it.animeId })
    }

    @Test
    fun `a discovery card says how much of the show there is to watch`() {
        val row = discover(popularNow = listOf(catalogue(1, episodes = 12, aired = 8))).popularNow
        assertEquals("8 серий", cardsOf(row).single().subtitle)
    }

    @Test
    fun `a finished show counts the whole season rather than what aired`() {
        val row = discover(
            popularNow = listOf(catalogue(1, episodes = 24, aired = 24, status = AnimeStatus.RELEASED)),
        ).popularNow
        assertEquals("24 серии", cardsOf(row).single().subtitle)
    }

    @Test
    fun `a title that has not started says who is making it`() {
        val row = discover(
            seasonal = listOf(catalogue(1, episodes = 12, aired = 0, status = AnimeStatus.ANONS)),
        ).seasonal
        assertEquals("Madhouse", cardsOf(row).single().subtitle)
    }

    @Test
    fun `a title with nothing aired and nobody named says nothing at all`() {
        val row = discover(
            seasonal = listOf(catalogue(1, aired = 0, status = AnimeStatus.ANONS, studio = null)),
        ).seasonal
        assertNull(cardsOf(row).single().subtitle)
    }

    @Test
    fun `a discovery card carries no episode badge and no progress`() {
        val card = cardsOf(discover(popularNow = listOf(catalogue(1))).popularNow).single()
        assertNull(card.badge)
        assertNull(card.progress)
    }

    @Test
    fun `the popular row that could not be read is absent rather than empty`() {
        assertNull(discover(popularNow = null).popularNow)
    }

    @Test
    fun `the popular row the catalogue had nothing for is absent too`() {
        assertNull(discover(popularNow = emptyList()).popularNow)
    }

    @Test
    fun `a row still loading keeps its place with a skeleton`() {
        val row = discover(loadingNow = true).popularNow!!
        assertEquals("Популярно сейчас", row.title)
        assertEquals(DiscoverContent.Loading, row.content)
    }

    @Test
    fun `titles already on screen are not replaced by a skeleton while they reload`() {
        val row = discover(popularNow = listOf(catalogue(1)), loadingNow = true).popularNow
        assertEquals(listOf(1), cardsOf(row).map { it.animeId })
    }

    // --- «Популярное в сезоне» ------------------------------------------------------------------

    @Test
    fun `the seasonal row is titled for the season, which the chips then name`() {
        assertEquals("Популярное в сезоне", discover(seasonal = listOf(catalogue(1))).seasonal!!.title)
    }

    @Test
    fun `the very first seasonal load failing takes the whole block away`() {
        assertNull(discover(seasonal = null, anySeasonLoaded = false).seasonal)
    }

    @Test
    fun `the first seasonal load keeps the block up while it is running`() {
        assertEquals(DiscoverContent.Loading, discover(loadingSeasonal = true).seasonal?.content)
    }

    @Test
    fun `a season with nothing in it keeps the switcher and says so`() {
        val row = discover(seasonal = emptyList(), anySeasonLoaded = true).seasonal
        assertEquals(DiscoverContent.Empty, row?.content)
    }

    @Test
    fun `a season that failed after another one worked keeps the switcher and offers a retry`() {
        val row = discover(seasonal = null, anySeasonLoaded = true).seasonal
        assertEquals(DiscoverContent.Failed, row?.content)
    }

    @Test
    fun `an empty season is empty however the session got there`() {
        // A 200 with nothing in it is an answer, so it never reads as a failure.
        assertEquals(DiscoverContent.Empty, discover(seasonal = emptyList()).seasonal?.content)
    }

    @Test
    fun `switching to a season that is loading shows a skeleton, not the season before it`() {
        val row = discover(seasonal = null, loadingSeasonal = true, anySeasonLoaded = true).seasonal
        assertEquals(DiscoverContent.Loading, row?.content)
    }

    // --- the rules that hold across both --------------------------------------------------------

    @Test
    fun `no discovery card joins its facts with a middle dot`() {
        val rows = discover(
            popularNow = listOf(catalogue(1), catalogue(2, aired = 0, status = AnimeStatus.ANONS)),
            seasonal = listOf(catalogue(3, episodes = 0, aired = 0, status = AnimeStatus.ANONS)),
        )
        val cards = cardsOf(rows.popularNow) + cardsOf(rows.seasonal)
        val text = cards.flatMap { listOfNotNull(it.title, it.subtitle) }
        assertTrue(text.isNotEmpty())
        assertTrue(text.none { it.contains("\u00B7") })
    }

    @Test
    fun `a home with no catalogue at all asks the screen to draw nothing`() {
        val rows = discover()
        assertNull(rows.popularNow)
        assertNull(rows.seasonal)
    }
}
