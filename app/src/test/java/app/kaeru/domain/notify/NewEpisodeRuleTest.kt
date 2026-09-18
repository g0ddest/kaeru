package app.kaeru.domain.notify

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Which episode is worth interrupting somebody for, with nothing Android in the way.
 *
 * The whole of the rule is here: the statuses that count, what «new» means when a check runs
 * every six hours, and the two ways an episode is recorded without being announced.
 */
class NewEpisodeRuleTest {
    private val now: Instant = Instant.parse("2026-09-18T12:00:00Z")

    private fun anime(
        id: Int,
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 24,
        aired: Int = 7,
    ) = Anime(id, "Аниме $id", "Anime $id", "poster-$id", emptyList(), status, episodes, aired, null, null, 2026, null, null)

    private fun entry(
        anime: Anime,
        status: ListStatus = ListStatus.WATCHING,
        watched: Int = 0,
        progress: List<EpisodeProgress> = emptyList(),
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, now), null, progress)

    private fun started(animeId: Int, episode: Int) =
        EpisodeProgress(animeId, episode, 600_000, 1_400_000, now)

    /** The rows a title already has, as the previous check left them. */
    private fun seen(animeId: Int, vararg episodes: Int) = episodes.map { NotifiedEpisode(animeId, it) }

    @Test
    fun `a title nobody is watching is never announced`() {
        val entries = listOf(
            entry(anime(1), status = ListStatus.PLANNED, watched = 0),
            entry(anime(2), status = ListStatus.DROPPED, watched = 3),
            entry(anime(3), status = ListStatus.ON_HOLD, watched = 3),
            entry(anime(4), status = ListStatus.COMPLETED, watched = 7),
        )

        val check = NewEpisodeRule.check(entries, known = emptyList())

        assertTrue(check.news.isEmpty())
        assertTrue(check.record.isEmpty())
    }

    @Test
    fun `the first sighting of a title says nothing and only writes down what it saw`() {
        val entries = listOf(entry(anime(1, aired = 7), watched = 3))

        val check = NewEpisodeRule.check(entries, known = emptyList())

        assertTrue(check.news.isEmpty())
        assertEquals(listOf(NotifiedEpisode(1, 7)), check.record)
    }

    @Test
    fun `an episode that has come out since the last check is the first one unwatched`() {
        val entries = listOf(entry(anime(1, aired = 8), watched = 7))

        val check = NewEpisodeRule.check(entries, known = seen(1, 7))

        assertEquals(
            listOf(NewEpisode(animeId = 1, title = "Аниме 1", posterUrl = "poster-1", episode = 8, aired = 8)),
            check.news,
        )
        assertEquals(listOf(NotifiedEpisode(1, 8)), check.record)
    }

    @Test
    fun `«Пересматриваю» counts the same as «Смотрю»`() {
        val entries = listOf(entry(anime(1, aired = 8), status = ListStatus.REWATCHING, watched = 7))

        val check = NewEpisodeRule.check(entries, known = seen(1, 7))

        assertEquals(listOf(8), check.news.map { it.episode })
    }

    @Test
    fun `the same check run again says nothing`() {
        val entries = listOf(entry(anime(1, aired = 8), watched = 7))
        val first = NewEpisodeRule.check(entries, known = seen(1, 7))

        val second = NewEpisodeRule.check(entries, known = seen(1, 7) + first.record)

        assertTrue(second.news.isEmpty())
        assertTrue(second.record.isEmpty())
    }

    @Test
    fun `an episode already begun is written down rather than announced`() {
        val entries = listOf(entry(anime(1, aired = 8), watched = 7, progress = listOf(started(1, 8))))

        val check = NewEpisodeRule.check(entries, known = seen(1, 7))

        assertTrue(check.news.isEmpty())
        assertEquals(listOf(NotifiedEpisode(1, 8)), check.record)
    }

    @Test
    fun `an episode merely opened by mistake is still news`() {
        val mistap = EpisodeProgress(1, 8, 9_000, 1_400_000, now)
        val entries = listOf(entry(anime(1, aired = 8), watched = 7, progress = listOf(mistap)))

        val check = NewEpisodeRule.check(entries, known = seen(1, 7))

        assertEquals(listOf(8), check.news.map { it.episode })
    }

    @Test
    fun `a viewer who is up to date is told nothing`() {
        val entries = listOf(entry(anime(1, aired = 7), watched = 7))

        val check = NewEpisodeRule.check(entries, known = seen(1, 7))

        assertTrue(check.news.isEmpty())
        assertTrue(check.record.isEmpty())
    }

    @Test
    fun `a viewer behind is told what aired, and offered the episode they are actually on`() {
        val behind = entry(anime(1, aired = 9), watched = 5)

        val check = NewEpisodeRule.check(listOf(behind), known = seen(1, 7))

        val news = check.news.single()
        assertEquals(9, news.aired)
        assertEquals(6, news.episode)
    }

    @Test
    fun `a viewer who is up to date is offered the episode that just aired`() {
        val entries = listOf(entry(anime(1, aired = 8), watched = 7))

        val news = NewEpisodeRule.check(entries, known = seen(1, 7)).news.single()

        assertEquals(8, news.aired)
        assertEquals(8, news.episode)
    }

    @Test
    fun `a viewer several episodes behind is told once, and not again as more come out`() {
        val behind = entry(anime(1, aired = 9), watched = 5)
        val first = NewEpisodeRule.check(listOf(behind), known = seen(1, 7))
        assertEquals(listOf(6), first.news.map { it.episode })

        val known = seen(1, 7) + first.record
        val later = NewEpisodeRule.check(listOf(entry(anime(1, aired = 10), watched = 5)), known = known)

        assertTrue(later.news.isEmpty())
        assertEquals(listOf(NotifiedEpisode(1, 10)), later.record)
    }

    @Test
    fun `a title that has announced nothing yet is left alone`() {
        val entries = listOf(entry(anime(1, status = AnimeStatus.ANONS, aired = 0), watched = 0))

        val check = NewEpisodeRule.check(entries, known = emptyList())

        assertTrue(check.news.isEmpty())
        assertTrue(check.record.isEmpty())
    }

    @Test
    fun `a finished title being caught up on never comes out again`() {
        val entries = listOf(entry(anime(1, status = AnimeStatus.RELEASED, episodes = 12, aired = 12), watched = 4))

        val check = NewEpisodeRule.check(entries, known = seen(1, 12))

        assertTrue(check.news.isEmpty())
        assertTrue(check.record.isEmpty())
    }

    @Test
    fun `two titles are two pieces of news`() {
        val entries = listOf(
            entry(anime(1, aired = 8), watched = 7),
            entry(anime(2, aired = 3), watched = 2),
        )

        val check = NewEpisodeRule.check(entries, known = seen(1, 7) + seen(2, 2))

        assertEquals(listOf(1 to 8, 2 to 3), check.news.map { it.animeId to it.episode })
    }
}
