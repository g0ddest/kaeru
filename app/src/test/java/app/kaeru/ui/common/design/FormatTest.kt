package app.kaeru.ui.common.design

import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.discover.seasonChoices
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class FormatTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    /** 12 April 2026, 21:30 Moscow — an ordinary evening on the sofa. */
    private val now: Instant = at(2026, 4, 12, 21, 30)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDate.of(year, month, day).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()

    private fun anime(
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 12,
        aired: Int = 8,
        nextEpisodeAt: Instant? = null,
    ) = Anime(
        id = 21,
        nameRu = "Магическая битва",
        nameRomaji = "Jujutsu Kaisen",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = nextEpisodeAt,
        score = 8.6,
        year = 2026,
        studio = "MAPPA",
        description = null,
    )

    private fun entry(
        anime: Anime = anime(),
        watched: Int = 6,
        watch: WatchState? = null,
        status: ListStatus = ListStatus.WATCHING,
    ) = LibraryEntry(anime, UserRate(1L, anime.id, status, watched, Instant.EPOCH), watch)

    private fun watch(episode: Int, positionMs: Long, durationMs: Long = 1_440_000) =
        WatchState(21, episode, positionMs, durationMs, null, null, Instant.EPOCH)

    // --- episodesLabel ---------------------------------------------------------------------

    @Test
    fun `episodes read as watched out of total`() {
        assertEquals("5 из 12", episodesLabel(5, 12))
    }

    @Test
    fun `an unknown season length keeps the question mark instead of a zero`() {
        assertEquals("5 из ?", episodesLabel(5, 0))
        assertEquals("0 из ?", episodesLabel(0, -1))
    }

    // --- pluralEpisodes --------------------------------------------------------------------

    @Test
    fun `the word for episode follows the Russian count`() {
        assertEquals("1 серия", pluralEpisodes(1))
        assertEquals("3 серии", pluralEpisodes(3))
        assertEquals("12 серий", pluralEpisodes(12))
        assertEquals("21 серия", pluralEpisodes(21))
        assertEquals("24 серии", pluralEpisodes(24))
        assertEquals("11 серий", pluralEpisodes(11))
    }

    // --- remainingLine ---------------------------------------------------------------------

    @Test
    fun `an unknown or finished episode has nothing left to say`() {
        assertNull(remainingLine(positionMs = 600_000, durationMs = 0))
        assertNull(remainingLine(positionMs = 1_440_000, durationMs = 1_440_000))
        assertNull(remainingLine(positionMs = 2_000_000, durationMs = 1_440_000))
    }

    @Test
    fun `the last seconds are a phrase, not a zero`() {
        assertEquals("осталось меньше минуты", remainingLine(1_410_000, 1_440_000))
    }

    @Test
    fun `minutes left are floored so the number is never a promise`() {
        assertEquals("осталось 14 мин", remainingLine(600_000, 1_440_000))
        assertEquals("осталось 14 мин", remainingLine(540_000, 1_440_000 - 41_000))
    }

    @Test
    fun `an hour or more reads in hours and minutes`() {
        assertEquals("осталось 1 ч 20 мин", remainingLine(0, 4_800_000))
        assertEquals("осталось 2 ч", remainingLine(0, 7_200_000))
    }

    // --- relativeDay -----------------------------------------------------------------------

    @Test
    fun `today and tomorrow are named, not counted`() {
        assertEquals("сегодня", relativeDay(at(2026, 4, 12, 23, 45), now, zone))
        assertEquals("завтра", relativeDay(at(2026, 4, 13, 1, 15), now, zone))
    }

    @Test
    fun `days ahead carry the right Russian plural`() {
        assertEquals("через 2 дня", relativeDay(at(2026, 4, 14, 12, 0), now, zone))
        assertEquals("через 3 дня", relativeDay(at(2026, 4, 15, 12, 0), now, zone))
        assertEquals("через 5 дней", relativeDay(at(2026, 4, 17, 12, 0), now, zone))
        assertEquals("через 11 дней", relativeDay(at(2026, 4, 23, 12, 0), now, zone))
        assertEquals("через 21 день", relativeDay(at(2026, 5, 3, 12, 0), now, zone))
    }

    @Test
    fun `the past is counted backwards the same way`() {
        assertEquals("вчера", relativeDay(at(2026, 4, 11, 3, 0), now, zone))
        assertEquals("3 дня назад", relativeDay(at(2026, 4, 9, 12, 0), now, zone))
        assertEquals("7 дней назад", relativeDay(at(2026, 4, 5, 12, 0), now, zone))
    }

    // --- episodeLine -----------------------------------------------------------------------

    @Test
    fun `a started episode says how much of it is left, after a comma`() {
        val item = FeedItem(entry(watch = watch(7, 600_000)), episode = 7, kind = FeedKind.CONTINUE)
        assertEquals("7 серия, осталось 14 мин", episodeLine(item, now, zone))
    }

    @Test
    fun `without a remembered position the episode stands alone`() {
        val item = FeedItem(entry(), episode = 7, kind = FeedKind.CONTINUE)
        assertEquals("7 серия", episodeLine(item, now, zone))
    }

    @Test
    fun `a fresh episode announces itself`() {
        val item = FeedItem(entry(), episode = 7, kind = FeedKind.NEW_EPISODE)
        assertEquals("Вышла 7 серия", episodeLine(item, now, zone))
    }

    @Test
    fun `the next episode to watch is just its number`() {
        val item = FeedItem(entry(), episode = 7, kind = FeedKind.NEXT_UP)
        assertEquals("7 серия", episodeLine(item, now, zone))
    }

    @Test
    fun `an episode still to air carries its day`() {
        val dated = entry(anime = anime(nextEpisodeAt = at(2026, 4, 13, 18, 0)))
        assertEquals("9 серия завтра", episodeLine(FeedItem(dated, 9, FeedKind.UPCOMING), now, zone))

        val undated = FeedItem(entry(), episode = 9, kind = FeedKind.UPCOMING)
        assertEquals("9 серия скоро", episodeLine(undated, now, zone))
    }

    @Test
    fun `a planned title reports the season, not an episode`() {
        val planned = entry(watched = 0, status = ListStatus.PLANNED)
        assertEquals("В планах, 12 серий", episodeLine(FeedItem(planned, 1, FeedKind.PLANNED), now, zone))

        val unknownLength = entry(anime = anime(episodes = 0, aired = 0), watched = 0, status = ListStatus.PLANNED)
        assertEquals("В планах", episodeLine(FeedItem(unknownLength, 1, FeedKind.PLANNED), now, zone))
    }

    @Test
    fun `no status line ever uses a middle dot separator`() {
        val lines = FeedKind.entries.map { kind ->
            episodeLine(FeedItem(entry(watch = watch(7, 600_000)), 7, kind), now, zone)
        }
        lines.forEach { line -> assertFalse(line, line.contains("·")) }
    }

    // --- primaryAction ---------------------------------------------------------------------

    @Test
    fun `nothing in the library gets the bare verb`() {
        val action = primaryAction(null, 0.9f, now, zone)
        assertEquals("Смотреть", action.label)
        assertTrue(action.enabled)
    }

    @Test
    fun `an untouched title starts at the first episode`() {
        val action = primaryAction(entry(watched = 0), 0.9f, now, zone)
        assertEquals("Смотреть 1 серию", action.label)
        assertTrue(action.enabled)
        assertEquals(1, action.episode)
    }

    @Test
    fun `a title in progress continues at the next episode`() {
        val action = primaryAction(entry(watched = 6), 0.9f, now, zone)
        assertEquals("Продолжить 7 серию", action.label)
        assertEquals(7, action.episode)
    }

    @Test
    fun `a half-watched episode continues from its timecode`() {
        val started = entry(watched = 6, watch = watch(7, 860_000))
        val action = primaryAction(started, 0.9f, now, zone)
        assertEquals("Продолжить с 14:20", action.label)
        assertTrue(action.enabled)
        assertEquals(7, action.episode)
    }

    @Test
    fun `an episode watched past the threshold moves on to the next one`() {
        val finished = entry(watched = 6, watch = watch(7, 1_400_000))
        assertEquals("Продолжить 8 серию", primaryAction(finished, 0.9f, now, zone).label)
    }

    @Test
    fun `the last episode that aired is still offered`() {
        // The boundary: eight of twelve are out and seven are behind the viewer.
        val action = primaryAction(entry(watched = 7), 0.9f, now, zone)
        assertEquals("Продолжить 8 серию", action.label)
        assertTrue(action.enabled)
        assertEquals(8, action.episode)
    }

    @Test
    fun `an episode that has not aired is named with its date and cannot be pressed`() {
        val waiting = entry(anime = anime(aired = 8, nextEpisodeAt = at(2026, 4, 13, 18, 0)), watched = 8)
        val action = primaryAction(waiting, 0.9f, now, zone)
        assertEquals("9 серия выйдет завтра", action.label)
        assertFalse(action.enabled)
        assertNull(action.episode)
    }

    @Test
    fun `an episode further out counts the days`() {
        val waiting = entry(anime = anime(aired = 8, nextEpisodeAt = at(2026, 4, 15, 18, 0)), watched = 8)
        assertEquals("9 серия выйдет через 3 дня", primaryAction(waiting, 0.9f, now, zone).label)
    }

    @Test
    fun `an episode with no date, and one whose date has already gone by, are simply awaited`() {
        val undated = entry(anime = anime(aired = 8, nextEpisodeAt = null), watched = 8)
        assertEquals("Ждём 9 серию", primaryAction(undated, 0.9f, now, zone).label)

        // The catalogue says it aired yesterday and still reports eight; promising a date would lie.
        val overdue = entry(anime = anime(aired = 8, nextEpisodeAt = at(2026, 4, 11, 18, 0)), watched = 8)
        val action = primaryAction(overdue, 0.9f, now, zone)
        assertEquals("Ждём 9 серию", action.label)
        assertFalse(action.enabled)
    }

    @Test
    fun `an announcement with nothing aired offers nothing to press`() {
        val anons = entry(anime = anime(AnimeStatus.ANONS, episodes = 0, aired = 0), watched = 0)
        val action = primaryAction(anons, 0.9f, now, zone)
        assertEquals("Ещё не вышло", action.label)
        assertFalse(action.enabled)
        assertNull(action.episode)
    }

    @Test
    fun `an announcement with a date names the day it arrives`() {
        val anons = entry(
            anime = anime(AnimeStatus.ANONS, episodes = 12, aired = 0, nextEpisodeAt = at(2026, 4, 13, 18, 0)),
            watched = 0,
        )
        val action = primaryAction(anons, 0.9f, now, zone)
        assertEquals("1 серия выйдет завтра", action.label)
        assertFalse(action.enabled)
    }

    @Test
    fun `a button that cannot be pressed never names an episode to play`() {
        val cases = listOf(
            entry(anime = anime(aired = 8, nextEpisodeAt = at(2026, 4, 13, 18, 0)), watched = 8),
            entry(anime = anime(aired = 8), watched = 8),
            entry(anime = anime(AnimeStatus.ANONS, episodes = 0, aired = 0), watched = 0),
            entry(watched = 6),
        )
        cases.forEach { case ->
            val action = primaryAction(case, 0.9f, now, zone)
            assertEquals(action.label, action.enabled, action.episode != null)
        }
    }

    // --- formatTime ------------------------------------------------------------------------

    @Test
    fun `a timecode drops the hour until there is one`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("14:20", formatTime(860_000))
        assertEquals("1:02:34", formatTime(3_754_000))
    }

    // --- pluralEpisodesAccusative ----------------------------------------------------------

    @Test
    fun `a verb puts the count in the accusative`() {
        assertEquals("1 серию", pluralEpisodesAccusative(1))
        assertEquals("2 серии", pluralEpisodesAccusative(2))
        assertEquals("4 серии", pluralEpisodesAccusative(4))
        assertEquals("5 серий", pluralEpisodesAccusative(5))
        assertEquals("21 серию", pluralEpisodesAccusative(21))
        assertEquals("22 серии", pluralEpisodesAccusative(22))
        assertEquals("60 серий", pluralEpisodesAccusative(60))
    }

    @Test
    fun `the accusative keeps the eleven to fourteen exception`() {
        assertEquals("11 серий", pluralEpisodesAccusative(11))
        assertEquals("12 серий", pluralEpisodesAccusative(12))
        assertEquals("14 серий", pluralEpisodesAccusative(14))
        assertEquals("111 серий", pluralEpisodesAccusative(111))
    }

    @Test
    fun `the two cases differ exactly where Russian says they do`() {
        assertEquals("1 серия", pluralEpisodes(1))
        assertEquals("1 серию", pluralEpisodesAccusative(1))
        assertEquals("21 серия", pluralEpisodes(21))
        assertEquals("21 серию", pluralEpisodesAccusative(21))
        // Everything else is spelled the same way in both.
        (2..10).forEach { assertEquals(pluralEpisodes(it), pluralEpisodesAccusative(it)) }
    }

    // --- seasonTitle ---------------------------------------------------------------------------

    @Test
    fun `a season chip names the season and the year`() {
        assertEquals("Зима 2026", seasonTitle(Season(SeasonKind.WINTER, 2026)))
        assertEquals("Весна 2026", seasonTitle(Season(SeasonKind.SPRING, 2026)))
        assertEquals("Лето 2026", seasonTitle(Season(SeasonKind.SUMMER, 2026)))
        assertEquals("Осень 2026", seasonTitle(Season(SeasonKind.FALL, 2026)))
    }

    @Test
    fun `a season chip is sentence case with no separator`() {
        val titles = seasonChoices(Season(SeasonKind.FALL, 2026)).map(::seasonTitle)
        assertEquals(listOf("Лето 2026", "Осень 2026", "Зима 2027"), titles)
        assertTrue(titles.none { it.contains("·") || it == it.uppercase() })
    }

    // --- statusLabel ---------------------------------------------------------------------------

    @Test
    fun `every list status has a Russian label in sentence case`() {
        assertEquals("Смотрю", statusLabel(ListStatus.WATCHING))
        assertEquals("В планах", statusLabel(ListStatus.PLANNED))
        assertEquals("Завершено", statusLabel(ListStatus.COMPLETED))
        assertEquals("Отложено", statusLabel(ListStatus.ON_HOLD))
        assertEquals("Брошено", statusLabel(ListStatus.DROPPED))
        assertEquals("Пересматриваю", statusLabel(ListStatus.REWATCHING))
    }

    /** The tabs and the title screen's menu both read these, so neither may shout or use a dot. */
    @Test
    fun `no status label shouts or carries a separator`() {
        ListStatus.entries.map(::statusLabel).forEach { label ->
            assertFalse(label, label.contains("·"))
            assertNotEquals(label, label.uppercase())
        }
    }
}
