package app.kaeru.domain.feed

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
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

/** The threshold most of these cases are not about; the ones that are pass their own. */
private const val DEFAULT = 0.9f

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
        progress: List<EpisodeProgress> = emptyList(),
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, updatedAt), watch, progress)

    private fun watching(animeId: Int, episode: Int, fraction: Float, at: Instant = now) =
        WatchState(animeId, episode, (fraction * 1_000_000).toLong(), 1_000_000, null, null, at)

    private fun stopped(animeId: Int, episode: Int, fraction: Float, at: Instant = now) =
        EpisodeProgress(animeId, episode, (fraction * 1_000_000).toLong(), 1_000_000, at)

    // --- positions kept per episode ---------------------------------------------------------

    @Test
    fun `a mis-tap on an earlier episode leaves the card on the episode being watched`() {
        val a = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(
                entry(
                    a, watched = 6,
                    // The sixth was opened last and holds ten seconds; the seventh holds forty
                    // minutes. The card belongs to the seventh.
                    watch = watching(1, 6, 0.01f),
                    progress = listOf(stopped(1, 7, 0.4f, now.minus(Duration.ofHours(2))), stopped(1, 6, 0.01f)),
                ),
            ),
            now, DEFAULT,
        )

        assertEquals(FeedKind.CONTINUE, feed.top?.kind)
        assertEquals(7, feed.top?.episode)
    }

    @Test
    fun `an episode nobody really started is no card at all`() {
        val a = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(entry(a, watched = 6, watch = watching(1, 7, 0.01f), progress = listOf(stopped(1, 7, 0.01f)))),
            now, DEFAULT,
        )

        assertTrue(feed.continueWatching.isEmpty())
        assertEquals(FeedKind.NEW_EPISODE, feed.top?.kind)
        assertEquals(7, feed.top?.episode)
    }

    @Test
    fun `the row is ordered by when each continued episode was last touched`() {
        val older = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val fresher = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(
                entry(older, watched = 6, progress = listOf(stopped(1, 7, 0.4f, now.minus(Duration.ofDays(2))))),
                entry(fresher, watched = 3, progress = listOf(stopped(2, 4, 0.4f, now.minus(Duration.ofMinutes(5))))),
            ),
            now, DEFAULT,
        )

        assertEquals(listOf(2, 1), feed.continueWatching.map { it.entry.anime.id })
    }

    @Test
    fun `an anime rises in the row when any of its episodes is touched, not only the target`() {
        // Going back to the sixth on purpose leaves the card pointing at the seventh — that is the
        // rule — but the title was plainly watched five minutes ago, and it must not sink below
        // one nobody has opened since yesterday.
        val revisited = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val untouched = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(
                entry(
                    revisited, watched = 5,
                    progress = listOf(
                        stopped(1, 7, 0.4f, now.minus(Duration.ofDays(2))),
                        stopped(1, 6, 0.3f, now.minus(Duration.ofMinutes(5))),
                    ),
                ),
                entry(untouched, watched = 3, progress = listOf(stopped(2, 4, 0.4f, now.minus(Duration.ofDays(1))))),
            ),
            now, DEFAULT,
        )

        assertEquals(listOf(1, 2), feed.continueWatching.map { it.entry.anime.id })
        assertEquals(7, feed.continueWatching.first().episode)
    }

    @Test
    fun `a tap on the wrong tile does not carry a title to the head of the row`() {
        // Ten seconds in the fourth episode is the newest row this title has, and it says nothing:
        // nobody watched it. The title that was genuinely left mid-episode yesterday comes first.
        val misTapped = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val watched = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(
                entry(
                    misTapped, watched = 6,
                    progress = listOf(
                        stopped(1, 7, 0.4f, now.minus(Duration.ofDays(3))),
                        stopped(1, 4, 0.005f, now.minus(Duration.ofMinutes(1))),
                    ),
                ),
                entry(watched, watched = 3, progress = listOf(stopped(2, 4, 0.4f, now.minus(Duration.ofDays(1))))),
            ),
            now, DEFAULT,
        )

        assertEquals(listOf(2, 1), feed.continueWatching.map { it.entry.anime.id })
        // And the card still points at the episode the viewer was really in.
        assertEquals(7, feed.continueWatching.last().episode)
    }

    @Test
    fun `a later episode outranks an earlier one still unfinished`() {
        val a = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val feed = builder.build(
            listOf(
                entry(
                    a, watched = 3,
                    progress = listOf(stopped(1, 4, 0.4f), stopped(1, 9, 0.2f)),
                ),
            ),
            now, DEFAULT,
        )

        assertEquals(9, feed.continueWatching.single().episode)
    }

    @Test
    fun `continue watching comes first and points at unfinished episode`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.4f))), now, DEFAULT)
        assertEquals(1, feed.continueWatching.size)
        assertEquals(5, feed.continueWatching[0].episode)
        assertEquals(FeedKind.CONTINUE, feed.top?.kind)
        assertEquals(5, feed.top?.episode)
    }

    @Test
    fun `the threshold the caller passes is the one the feed obeys`() {
        // The setting lives on the screen and changes while this singleton is alive, so it is an
        // argument rather than a field. Eighty-five percent of the sixth is a place to come back
        // to at 0.9 and the end of that episode at 0.8.
        val a = anime(1, AnimeStatus.ONGOING, episodes = 24, aired = 10)
        val entries = listOf(entry(a, watched = 5, progress = listOf(stopped(1, 6, 0.85f))))

        val strict = builder.build(entries, now, 0.9f)
        assertEquals(FeedKind.CONTINUE, strict.top?.kind)
        assertEquals(6, strict.top?.episode)

        val lenient = builder.build(entries, now, 0.8f)
        assertEquals(FeedKind.NEW_EPISODE, lenient.top?.kind)
        assertEquals(7, lenient.top?.episode)
    }

    @Test
    fun `episode watched past threshold advances locally before shikimori sync`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.95f))), now, DEFAULT)
        assertTrue(feed.continueWatching.isEmpty())
        assertEquals(FeedKind.NEXT_UP, feed.top?.kind)
        assertEquals(6, feed.top?.episode)
    }

    @Test
    fun `new episodes are ongoing titles with aired ahead of watched`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val caughtUp = anime(3, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val feed = builder.build(listOf(entry(ongoing, watched = 6), entry(caughtUp, watched = 7)), now, DEFAULT)
        assertEquals(listOf(2), feed.newEpisodes.map { it.entry.anime.id })
        assertEquals(7, feed.newEpisodes[0].episode)
        assertEquals(FeedKind.NEW_EPISODE, feed.newEpisodes[0].kind)
    }

    @Test
    fun `new episode beats next up for the top card when nothing is in progress`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val released = anime(1)
        val feed = builder.build(listOf(entry(released, watched = 3), entry(ongoing, watched = 6)), now, DEFAULT)
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
            ), now, DEFAULT,
        )
        assertEquals(listOf(2, 1), feed.nextUp.map { it.entry.anime.id })
        assertEquals(2, feed.nextUp[0].episode)
    }

    @Test
    fun `an announcement never lands in next up, however many episodes it promises`() {
        val announced = anime(1, AnimeStatus.ANONS, episodes = 12, aired = 0)
        val feed = builder.build(listOf(entry(announced, watched = 0)), now, DEFAULT)
        assertTrue(feed.nextUp.isEmpty())
    }

    @Test
    fun `upcoming is ongoing watching with next episode within window`() {
        val soon = anime(2, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(2)))
        val far = anime(3, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(20)))
        val feed = builder.build(listOf(entry(soon, watched = 7), entry(far, watched = 7)), now, DEFAULT)
        assertEquals(listOf(2), feed.upcoming.map { it.entry.anime.id })
        assertEquals(8, feed.upcoming[0].episode)
    }

    @Test
    fun `planned titles fill the planned row and never the top card`() {
        val feed = builder.build(listOf(entry(anime(9), status = ListStatus.PLANNED)), now, DEFAULT)
        assertEquals(1, feed.planned.size)
        assertEquals(1, feed.planned[0].episode)
        assertNull(feed.top)
    }

    @Test
    fun `completed and dropped titles are ignored`() {
        val feed = builder.build(
            listOf(entry(anime(1), ListStatus.COMPLETED, watched = 12), entry(anime(2), ListStatus.DROPPED, watched = 2)), now, DEFAULT,
        )
        assertTrue(feed.isEmpty)
    }

    @Test
    fun `fully watched released title without shikimori update is not next up`() {
        val a = anime(1, episodes = 12)
        val feed = builder.build(listOf(entry(a, watched = 12)), now, DEFAULT)
        assertTrue(feed.nextUp.isEmpty())
    }

    @Test
    fun `a released title finished on this device is not next up either`() {
        // The same finished show, reached the other way: every episode watched here and Shikimori
        // still counting six. The viewer sees one show, so the feed has to treat it as one.
        val a = anime(1, episodes = 12)
        val feed = builder.build(
            listOf(entry(a, watched = 6, progress = (1..12).map { stopped(1, it, 0.95f) })),
            now, DEFAULT,
        )

        assertTrue(feed.nextUp.isEmpty())
        assertNull(feed.top)
    }

    @Test
    fun `a rewatcher who has reset their count is watching the show, not finished with it`() {
        // «Пересматриваю» with the counter back at zero: twelve finished rows from the last time
        // round say nothing about this one, and the first episode is genuinely next.
        val a = anime(1, episodes = 12)
        val feed = builder.build(
            listOf(
                entry(
                    a, ListStatus.REWATCHING, watched = 0,
                    progress = (1..12).map { stopped(1, it, 0.95f) },
                ),
            ),
            now, DEFAULT,
        )

        assertEquals(listOf(1), feed.nextUp.map { it.entry.anime.id })
        assertEquals(1, feed.nextUp[0].episode)
    }
}
