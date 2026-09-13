package app.kaeru.ui.common.design

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
import org.junit.Assert.assertNull
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

    // --- primaryActionLabel ----------------------------------------------------------------

    @Test
    fun `nothing in the library gets the bare verb`() {
        assertEquals("Смотреть", primaryActionLabel(null, 0.9f))
    }

    @Test
    fun `an untouched title starts at the first episode`() {
        assertEquals("Смотреть 1 серию", primaryActionLabel(entry(watched = 0), 0.9f))
    }

    @Test
    fun `a title in progress continues at the next episode`() {
        assertEquals("Продолжить 7 серию", primaryActionLabel(entry(watched = 6), 0.9f))
    }

    @Test
    fun `a half-watched episode continues from its timecode`() {
        val started = entry(watched = 6, watch = watch(7, 860_000))
        assertEquals("Продолжить с 14:20", primaryActionLabel(started, 0.9f))
    }

    @Test
    fun `an episode watched past the threshold moves on to the next one`() {
        val finished = entry(watched = 6, watch = watch(7, 1_400_000))
        assertEquals("Продолжить 8 серию", primaryActionLabel(finished, 0.9f))
    }

    // --- formatTime ------------------------------------------------------------------------

    @Test
    fun `a timecode drops the hour until there is one`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("14:20", formatTime(860_000))
        assertEquals("1:02:34", formatTime(3_754_000))
    }
}
