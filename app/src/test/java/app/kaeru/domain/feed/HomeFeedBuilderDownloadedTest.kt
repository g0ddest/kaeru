package app.kaeru.domain.feed

import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

private const val DEFAULT = 0.9f

/**
 * «Скачано»: what is on the device and still ahead of the viewer.
 *
 * The row answers one question — what can I start right now with no network — so an episode
 * already behind the viewer is not in it however recently it was downloaded, and a download that
 * has not finished is not either.
 */
class HomeFeedBuilderDownloadedTest {
    private val now: Instant = Instant.parse("2026-09-12T12:00:00Z")
    private val builder = HomeFeedBuilder()

    private fun anime(id: Int, episodes: Int = 12) = Anime(
        id, "Аниме $id", "Anime $id", null, emptyList(),
        AnimeStatus.ONGOING, episodes, episodes, null, null, 2026, null, null,
    )

    private fun entry(
        anime: Anime,
        watched: Int = 0,
        status: ListStatus = ListStatus.WATCHING,
        progress: List<EpisodeProgress> = emptyList(),
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, now), null, progress)

    private fun stopped(animeId: Int, episode: Int, fraction: Float) =
        EpisodeProgress(animeId, episode, (fraction * 1_000_000).toLong(), 1_000_000, now)

    private fun download(
        animeId: Int,
        episode: Int,
        state: DownloadState = DownloadState.COMPLETED,
        at: Instant = now,
    ) = EpisodeDownload(
        key = DownloadKey(animeId, episode, translationId = 7, quality = Quality.P720),
        state = state,
        bytes = 320L * 1024 * 1024,
        progress = if (state == DownloadState.COMPLETED) 1f else 0.4f,
        failure = null,
        updatedAt = at,
    )

    @Test
    fun `a finished download of an unwatched episode is a card`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 3)), now, DEFAULT, listOf(download(1, 4)))

        assertEquals(1, feed.downloaded.size)
        assertEquals(FeedKind.DOWNLOADED, feed.downloaded.single().kind)
        assertEquals(4, feed.downloaded.single().episode)
        assertEquals(1, feed.downloaded.single().entry.anime.id)
    }

    @Test
    fun `the newest download comes first`() {
        val a = anime(1)
        val b = anime(2)
        val feed = builder.build(
            listOf(entry(a), entry(b)),
            now,
            DEFAULT,
            listOf(
                download(1, 1, at = now.minus(Duration.ofHours(3))),
                download(2, 5, at = now.minus(Duration.ofMinutes(10))),
                download(1, 2, at = now.minus(Duration.ofHours(1))),
            ),
        )

        assertEquals(listOf(2 to 5, 1 to 2, 1 to 1), feed.downloaded.map { it.entry.anime.id to it.episode })
    }

    @Test
    fun `an episode Shikimori has already counted is not offered again`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4)), now, DEFAULT, listOf(download(1, 4)))

        assertTrue(feed.downloaded.isEmpty())
    }

    @Test
    fun `an episode watched past the threshold on this device is not offered either`() {
        val a = anime(1)
        val feed = builder.build(
            listOf(entry(a, watched = 0, progress = listOf(stopped(1, 4, 0.95f)))),
            now, DEFAULT, listOf(download(1, 4)),
        )

        assertTrue(feed.downloaded.isEmpty())
    }

    @Test
    fun `an episode left half-watched is still something to come back to`() {
        val a = anime(1)
        val feed = builder.build(
            listOf(entry(a, watched = 0, progress = listOf(stopped(1, 4, 0.4f)))),
            now, DEFAULT, listOf(download(1, 4)),
        )

        assertEquals(listOf(4), feed.downloaded.map { it.episode })
    }

    @Test
    fun `the threshold is the one passed in, not a fixed nine tenths`() {
        val a = anime(1)
        val entries = listOf(entry(a, watched = 0, progress = listOf(stopped(1, 4, 0.85f))))

        assertTrue(builder.build(entries, now, 0.8f, listOf(download(1, 4))).downloaded.isEmpty())
        assertEquals(1, builder.build(entries, now, 0.95f, listOf(download(1, 4))).downloaded.size)
    }

    @Test
    fun `a download still running is not on the row`() {
        val a = anime(1)
        val running = listOf(
            download(1, 4, DownloadState.DOWNLOADING),
            download(1, 5, DownloadState.QUEUED),
            download(1, 6, DownloadState.FAILED),
            download(1, 7, DownloadState.WAITING_FOR_WIFI),
        )

        assertTrue(builder.build(listOf(entry(a)), now, DEFAULT, running).downloaded.isEmpty())
    }

    @Test
    fun `a download of a title the list knows nothing about has no card to draw`() {
        val feed = builder.build(listOf(entry(anime(1))), now, DEFAULT, listOf(download(99, 1)))

        assertTrue(feed.downloaded.isEmpty())
    }

    @Test
    fun `nothing downloaded is an empty row rather than a heading`() {
        assertTrue(builder.build(listOf(entry(anime(1))), now, DEFAULT, emptyList()).downloaded.isEmpty())
    }

    @Test
    fun `a downloaded episode never takes the hero from what the viewer was watching`() {
        val a = anime(1)
        val feed = builder.build(
            listOf(entry(a, watched = 3, progress = listOf(stopped(1, 4, 0.4f)))),
            now, DEFAULT, listOf(download(1, 9)),
        )

        assertEquals(FeedKind.CONTINUE, feed.top?.kind)
        assertEquals(4, feed.top?.episode)
    }

    @Test
    fun `a home with only downloads on it is not an empty home`() {
        val a = anime(1).copy(status = AnimeStatus.RELEASED)
        val feed = builder.build(
            listOf(entry(a, watched = 0, status = ListStatus.COMPLETED)),
            now, DEFAULT, listOf(download(1, 1)),
        )

        assertEquals(1, feed.downloaded.size)
        assertTrue(!feed.isEmpty)
    }
}
