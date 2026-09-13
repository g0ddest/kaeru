package app.kaeru.ui.mobile.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
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
    ) = LibraryEntry(
        anime = anime(id, episodes, aired, nextAt, status),
        rate = UserRate(id.toLong(), id, ListStatus.WATCHING, watched, now),
        watch = watch,
    )

    private fun watch(id: Int, episode: Int, positionMs: Long, durationMs: Long) =
        WatchState(id, episode, positionMs, durationMs, null, null, now)

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

    private fun catalogue(
        id: Int,
        episodes: Int = 12,
        aired: Int = 8,
        status: AnimeStatus = AnimeStatus.ONGOING,
        studio: String? = "Madhouse",
    ) = anime(id, episodes, aired, null, status).copy(studio = studio)

    @Test
    fun `the popular row is titled for what is airing rather than for the catalogue`() {
        val row = popularNowRow(listOf(catalogue(1)), loading = false)!!
        assertEquals("Популярно сейчас", row.title)
        assertEquals(listOf(1), row.cards.map { it.animeId })
        assertFalse(row.loading)
    }

    @Test
    fun `the seasonal row is titled for the season, which the chips then name`() {
        assertEquals("Популярное в сезоне", seasonalRow(listOf(catalogue(1)), loading = false)!!.title)
    }

    @Test
    fun `a discovery card says how much of the show there is to watch`() {
        val card = popularNowRow(listOf(catalogue(1, episodes = 12, aired = 8)), loading = false)!!.cards.single()
        assertEquals("8 серий", card.subtitle)
    }

    @Test
    fun `a finished show counts the whole season rather than what aired`() {
        val card = popularNowRow(
            listOf(catalogue(1, episodes = 24, aired = 24, status = AnimeStatus.RELEASED)),
            loading = false,
        )!!.cards.single()
        assertEquals("24 серии", card.subtitle)
    }

    @Test
    fun `a title that has not started says who is making it`() {
        val card = seasonalRow(
            listOf(catalogue(1, episodes = 12, aired = 0, status = AnimeStatus.ANONS)),
            loading = false,
        )!!.cards.single()
        assertEquals("Madhouse", card.subtitle)
    }

    @Test
    fun `a title with nothing aired and nobody named says nothing at all`() {
        val card = seasonalRow(
            listOf(catalogue(1, aired = 0, status = AnimeStatus.ANONS, studio = null)),
            loading = false,
        )!!.cards.single()
        assertNull(card.subtitle)
    }

    @Test
    fun `a discovery card carries no episode badge and no progress`() {
        val card = popularNowRow(listOf(catalogue(1)), loading = false)!!.cards.single()
        assertNull(card.badge)
        assertNull(card.progress)
    }

    @Test
    fun `a row that could not be read is absent rather than empty`() {
        assertNull(popularNowRow(null, loading = false))
        assertNull(seasonalRow(null, loading = false))
    }

    @Test
    fun `a row the catalogue had nothing for is absent too`() {
        assertNull(popularNowRow(emptyList(), loading = false))
        assertNull(seasonalRow(emptyList(), loading = false))
    }

    @Test
    fun `a row still loading keeps its place with nothing in it`() {
        val row = popularNowRow(null, loading = true)!!
        assertTrue(row.loading)
        assertTrue(row.cards.isEmpty())
        assertEquals("Популярно сейчас", row.title)
    }

    @Test
    fun `titles already on screen are not replaced by a skeleton while they reload`() {
        val row = popularNowRow(listOf(catalogue(1)), loading = true)!!
        assertFalse(row.loading)
        assertEquals(listOf(1), row.cards.map { it.animeId })
    }

    @Test
    fun `no discovery card joins its facts with a middle dot`() {
        val rows = listOfNotNull(
            popularNowRow(listOf(catalogue(1), catalogue(2, aired = 0, status = AnimeStatus.ANONS)), loading = false),
            seasonalRow(listOf(catalogue(3, episodes = 0, aired = 0, status = AnimeStatus.ANONS)), loading = false),
        )
        val text = rows.flatMap { it.cards }.flatMap { listOfNotNull(it.title, it.subtitle) }
        assertTrue(text.isNotEmpty())
        assertTrue(text.none { it.contains("·") })
    }
}
