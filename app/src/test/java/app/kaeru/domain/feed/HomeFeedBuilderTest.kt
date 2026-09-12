package app.kaeru.domain.feed

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HomeFeedBuilderTest {
    private val now: Instant = Instant.parse("2026-09-12T12:00:00Z")
    private val builder = HomeFeedBuilder()

    private fun anime(
        id: Int, status: AnimeStatus = AnimeStatus.RELEASED, episodes: Int = 12,
        aired: Int = episodes, next: Instant? = null,
    ) = Anime(id, "Аниме $id", "Anime $id", null, emptyList(), status, episodes, aired, next, null, 2026, null, null)

    private fun entry(
        anime: Anime, status: ListStatus = ListStatus.WATCHING, watched: Int = 0,
        updatedAt: Instant = now, watch: WatchState? = null,
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, updatedAt), watch)

    private fun watching(animeId: Int, episode: Int, fraction: Float, at: Instant = now) =
        WatchState(animeId, episode, (fraction * 1_000_000).toLong(), 1_000_000, null, null, at)

    @Test
    fun `continue watching comes first and points at unfinished episode`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.4f))), now)
        assertEquals(1, feed.continueWatching.size)
        assertEquals(5, feed.continueWatching[0].episode)
        assertEquals(FeedKind.CONTINUE, feed.top?.kind)
        assertEquals(5, feed.top?.episode)
    }

    @Test
    fun `episode watched past threshold advances locally before shikimori sync`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.95f))), now)
        assertTrue(feed.continueWatching.isEmpty())
        assertEquals(FeedKind.NEXT_UP, feed.top?.kind)
        assertEquals(6, feed.top?.episode)
    }

    @Test
    fun `new episodes are ongoing titles with aired ahead of watched`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val caughtUp = anime(3, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val feed = builder.build(listOf(entry(ongoing, watched = 6), entry(caughtUp, watched = 7)), now)
        assertEquals(listOf(2), feed.newEpisodes.map { it.entry.anime.id })
        assertEquals(7, feed.newEpisodes[0].episode)
        assertEquals(FeedKind.NEW_EPISODE, feed.newEpisodes[0].kind)
    }

    @Test
    fun `new episode beats next up for the top card when nothing is in progress`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val released = anime(1)
        val feed = builder.build(listOf(entry(released, watched = 3), entry(ongoing, watched = 6)), now)
        assertEquals(2, feed.top?.entry?.anime?.id)
        assertEquals(FeedKind.NEW_EPISODE, feed.top?.kind)
    }

    @Test
    fun `next up lists released titles with unwatched episodes sorted by recent activity`() {
        val old = anime(1)
        val fresh = anime(2)
        val feed = builder.build(
            listOf(
                entry(old, watched = 3, updatedAt = now.minus(Duration.ofDays(3))),
                entry(fresh, watched = 1, updatedAt = now.minus(Duration.ofHours(1))),
            ), now,
        )
        assertEquals(listOf(2, 1), feed.nextUp.map { it.entry.anime.id })
        assertEquals(2, feed.nextUp[0].episode)
    }

    @Test
    fun `upcoming is ongoing watching with next episode within window`() {
        val soon = anime(2, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(2)))
        val far = anime(3, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(20)))
        val feed = builder.build(listOf(entry(soon, watched = 7), entry(far, watched = 7)), now)
        assertEquals(listOf(2), feed.upcoming.map { it.entry.anime.id })
        assertEquals(8, feed.upcoming[0].episode)
    }

    @Test
    fun `planned titles fill the planned row and never the top card`() {
        val feed = builder.build(listOf(entry(anime(9), status = ListStatus.PLANNED)), now)
        assertEquals(1, feed.planned.size)
        assertEquals(1, feed.planned[0].episode)
        assertNull(feed.top)
    }

    @Test
    fun `completed and dropped titles are ignored`() {
        val feed = builder.build(
            listOf(entry(anime(1), ListStatus.COMPLETED, watched = 12), entry(anime(2), ListStatus.DROPPED, watched = 2)), now,
        )
        assertTrue(feed.isEmpty)
    }

    @Test
    fun `fully watched released title without shikimori update is not next up`() {
        val a = anime(1, episodes = 12)
        val feed = builder.build(listOf(entry(a, watched = 12)), now)
        assertTrue(feed.nextUp.isEmpty())
    }
}
