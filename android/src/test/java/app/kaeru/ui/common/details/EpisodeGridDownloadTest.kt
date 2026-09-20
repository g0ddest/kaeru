package app.kaeru.ui.common.details

import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What a season grid knows about downloads: one state and one fraction per cell, and which cells
 * the «Скачать…» sheet may still offer.
 */
class EpisodeGridDownloadTest {
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")

    private fun anime(episodes: Int = 12, aired: Int = episodes) = Anime(
        id = 1, nameRu = "Аниме", nameRomaji = "Anime", posterUrl = null, screenshotUrls = emptyList(),
        status = AnimeStatus.ONGOING, episodes = episodes, episodesAired = aired, nextEpisodeAt = null,
        score = null, year = 2026, studio = null, description = null,
    )

    private fun rate(watched: Int) = UserRate(1, 1, ListStatus.WATCHING, watched, now)

    private fun download(episode: Int, state: DownloadState, progress: Float = 0f) = EpisodeDownload(
        key = DownloadKey(1, episode, translationId = 7, quality = Quality.P720),
        state = state,
        bytes = 320L * 1024 * 1024,
        progress = progress,
        failure = null,
        updatedAt = now,
    )

    private fun cells(downloads: List<EpisodeDownload>) =
        episodeCells(anime(), rate(0), null, emptyList(), 0.9f, downloads)

    @Test
    fun `a cell with no download carries neither a state nor a fraction`() {
        val cell = cells(emptyList()).first { it.number == 3 }

        assertNull(cell.download)
        assertNull(cell.downloadProgress)
    }

    @Test
    fun `a queued episode carries its state and no fraction`() {
        val cell = cells(listOf(download(3, DownloadState.QUEUED))).first { it.number == 3 }

        assertEquals(DownloadState.QUEUED, cell.download)
        assertNull(cell.downloadProgress)
    }

    @Test
    fun `a running download carries the fraction the ring draws`() {
        val cell = cells(listOf(download(3, DownloadState.DOWNLOADING, 0.42f))).first { it.number == 3 }

        assertEquals(DownloadState.DOWNLOADING, cell.download)
        assertEquals(0.42f, cell.downloadProgress!!, 0.0001f)
    }

    @Test
    fun `a finished download is a state with no ring to draw`() {
        val cell = cells(listOf(download(3, DownloadState.COMPLETED, 1f))).first { it.number == 3 }

        assertEquals(DownloadState.COMPLETED, cell.download)
        assertNull(cell.downloadProgress)
    }

    @Test
    fun `each download lands on its own episode and nowhere else`() {
        val grid = cells(
            listOf(download(2, DownloadState.COMPLETED, 1f), download(5, DownloadState.DOWNLOADING, 0.1f)),
        )

        assertEquals(DownloadState.COMPLETED, grid.first { it.number == 2 }.download)
        assertEquals(DownloadState.DOWNLOADING, grid.first { it.number == 5 }.download)
        assertTrue(grid.filter { it.number !in setOf(2, 5) }.all { it.download == null })
    }

    @Test
    fun `a download of an episode past the end of the season does not invent a cell`() {
        val grid = episodeCells(
            anime(episodes = 3, aired = 3), rate(0), null, emptyList(), 0.9f,
            listOf(download(9, DownloadState.COMPLETED, 1f)),
        )

        assertEquals(3, grid.size)
    }

    // --- what the «Скачать…» sheet may offer ----------------------------------------------------

    @Test
    fun `the sheet offers aired episodes that are not on the device`() {
        val grid = episodeCells(
            anime(episodes = 12, aired = 4), rate(2), null, emptyList(), 0.9f,
            listOf(download(1, DownloadState.COMPLETED, 1f), download(2, DownloadState.DOWNLOADING, 0.5f)),
        )

        assertEquals(listOf(3, 4), downloadChoices(grid).map { it.episode })
    }

    @Test
    fun `the sheet says which of them the viewer has already seen`() {
        val grid = episodeCells(anime(episodes = 4, aired = 4), rate(2), null, emptyList(), 0.9f, emptyList())

        assertEquals(
            listOf(1 to true, 2 to true, 3 to false, 4 to false),
            downloadChoices(grid).map { it.episode to it.watched },
        )
    }

    @Test
    fun `a failed download is something to offer again`() {
        val grid = episodeCells(
            anime(episodes = 2, aired = 2), rate(0), null, emptyList(), 0.9f,
            listOf(download(1, DownloadState.FAILED)),
        )

        assertEquals(listOf(1, 2), downloadChoices(grid).map { it.episode })
    }
}
