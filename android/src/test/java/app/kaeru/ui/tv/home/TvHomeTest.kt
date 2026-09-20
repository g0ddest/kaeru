package app.kaeru.ui.tv.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.ui.common.home.HomeCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2026-09-13T20:00:00Z")
private val UTC = ZoneOffset.UTC

class TvHomeTest {

    private fun anime(
        id: Int = 1,
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 12,
        aired: Int = 8,
        screenshots: List<String> = emptyList(),
        poster: String? = "poster-$id",
        nextAt: Instant? = null,
    ) = Anime(
        id = id,
        nameRu = "Аниме $id",
        nameRomaji = "Anime $id",
        posterUrl = poster,
        screenshotUrls = screenshots,
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = nextAt,
        score = null,
        year = 2026,
        studio = null,
        description = null,
    )

    private fun entry(
        anime: Anime = anime(),
        watched: Int = 4,
        watch: WatchState? = null,
        status: ListStatus = ListStatus.WATCHING,
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, Instant.EPOCH), watch)

    private fun watch(episode: Int, position: Long, duration: Long) =
        WatchState(1, episode, position, duration, null, null, Instant.EPOCH)

    // --- the picture behind the hero ----------------------------------------------------------

    @Test
    fun `backdrop prefers a screenshot over the poster`() {
        assertEquals("shot", tvBackdrop(anime(screenshots = listOf("shot", "other"))))
    }

    @Test
    fun `backdrop falls back to the poster when the catalogue has no screenshot`() {
        assertEquals("poster-1", tvBackdrop(anime(screenshots = emptyList())))
    }

    @Test
    fun `backdrop is absent when the catalogue has neither`() {
        assertNull(tvBackdrop(anime(poster = null)))
    }

    // --- hero text ------------------------------------------------------------------------------

    @Test
    fun `hero of a started episode offers the position and says where the viewer is`() {
        val item = FeedItem(
            entry(watch = watch(episode = 5, position = 600_000, duration = 1_440_000)),
            episode = 5,
            kind = FeedKind.CONTINUE,
        )
        val hero = tvHero(item, threshold = 0.9f, now = NOW, zone = UTC)
        assertEquals("Аниме 1", hero.title)
        assertEquals("Продолжить с 10:00", hero.action)
        assertEquals("5 серия, осталось 14 мин", hero.meta)
    }

    @Test
    fun `hero of a new episode names the episode to start`() {
        val item = FeedItem(entry(watched = 2), episode = 3, kind = FeedKind.NEW_EPISODE)
        val hero = tvHero(item, threshold = 0.9f, now = NOW, zone = UTC)
        assertEquals("Продолжить 3 серию", hero.action)
        assertEquals("Вышла 3 серия", hero.meta)
    }

    /**
     * The one place the two lines would say the same thing twice: nothing can be started, and
     * the action's own label is already the better sentence about why.
     */
    @Test
    fun `hero of an unaired episode says when it lands and offers nothing`() {
        val soon = NOW.plus(Duration.ofDays(1))
        val item = FeedItem(
            entry(anime = anime(aired = 8, nextAt = soon), watched = 8),
            episode = 9,
            kind = FeedKind.UPCOMING,
        )
        val hero = tvHero(item, threshold = 0.9f, now = NOW, zone = UTC)
        assertNull(hero.action)
        assertEquals("9 серия выйдет завтра", hero.meta)
    }

    @Test
    fun `hero of a catalogue card offers nothing to start and keeps the card's own line`() {
        val hero = tvHero(HomeCard(7, "Дандадан", "poster-7", subtitle = "12 серий"))
        assertEquals("Дандадан", hero.title)
        assertEquals("12 серий", hero.meta)
        assertNull(hero.action)
        assertEquals("poster-7", hero.backdropUrl)
    }

    // --- rows ------------------------------------------------------------------------------------

    @Test
    fun `rows carry the phone's headings in the phone's order`() {
        val new = FeedItem(entry(anime = anime(id = 1)), 3, FeedKind.NEW_EPISODE)
        val cont = FeedItem(entry(anime = anime(id = 2), watch = watch(2, 60_000, 1_440_000)), 2, FeedKind.CONTINUE)
        val planned = FeedItem(entry(anime = anime(id = 3), status = ListStatus.PLANNED, watched = 0), 1, FeedKind.PLANNED)
        val feed = HomeFeed(cont, listOf(cont), listOf(new), emptyList(), emptyList(), listOf(planned))
        val rows = tvHomeRows(feed, threshold = 0.9f, now = NOW, zone = UTC)
        assertEquals(listOf("Новые серии", "Продолжить", "В планах"), rows.map { it.title })
    }

    @Test
    fun `a card carries the badge and the strip the phone gives it`() {
        val cont = FeedItem(entry(watch = watch(5, 720_000, 1_440_000)), 5, FeedKind.CONTINUE)
        val feed = HomeFeed(cont, listOf(cont), emptyList(), emptyList(), emptyList(), emptyList())
        val card = tvHomeRows(feed, 0.9f, NOW, UTC).single().items.single()
        assertEquals("5 серия", card.badge)
        assertEquals(0.5f, card.progress!!, 0.001f)
        assertEquals("Аниме 1", card.title)
    }

    /** One press plays; only an episode there is no point starting opens the card instead. */
    @Test
    fun `a card that can be played says which episode one press starts`() {
        val next = FeedItem(entry(anime = anime(status = AnimeStatus.RELEASED, episodes = 12, aired = 12)), 5, FeedKind.NEXT_UP)
        val feed = HomeFeed(next, emptyList(), emptyList(), listOf(next), emptyList(), emptyList())
        assertEquals(5, tvHomeRows(feed, 0.9f, NOW, UTC).single().items.single().playEpisode)
    }

    @Test
    fun `a card whose episode has not aired starts nothing`() {
        val soon = FeedItem(entry(anime = anime(aired = 8, nextAt = NOW.plus(Duration.ofDays(2)))), 9, FeedKind.UPCOMING)
        val feed = HomeFeed(null, emptyList(), emptyList(), emptyList(), listOf(soon), emptyList())
        assertNull(tvHomeRows(feed, 0.9f, NOW, UTC).single().items.single().playEpisode)
    }

    /**
     * The one row where «what this card is about» and «what the watch button would do» come apart:
     * four aired episodes nobody has watched, and a card in «Скоро» about the fifth.
     */
    @Test
    fun `an upcoming card offers nothing even when earlier episodes are unwatched`() {
        val soon = FeedItem(
            entry(anime = anime(aired = 4, nextAt = NOW.plus(Duration.ofDays(1))), watched = 0),
            episode = 5,
            kind = FeedKind.UPCOMING,
        )
        val feed = HomeFeed(null, emptyList(), emptyList(), emptyList(), listOf(soon), emptyList())
        val card = tvHomeRows(feed, 0.9f, NOW, UTC).single().items.single()
        assertNull(card.playEpisode)
        assertNull(card.hero.action)
        assertEquals("5 серия завтра", card.hero.meta)
    }

    @Test
    fun `every card carries the hero its own focus will show`() {
        val cont = FeedItem(entry(watch = watch(5, 600_000, 1_440_000)), 5, FeedKind.CONTINUE)
        val feed = HomeFeed(cont, listOf(cont), emptyList(), emptyList(), emptyList(), emptyList())
        val card = tvHomeRows(feed, 0.9f, NOW, UTC).single().items.single()
        assertEquals(tvHero(cont, 0.9f, NOW, UTC), card.hero)
    }

    @Test
    fun `an empty feed has no rows at all`() {
        assertEquals(emptyList<TvHomeRow>(), tvHomeRows(HomeFeed.EMPTY, 0.9f, NOW, UTC))
    }
}
