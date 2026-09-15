package app.kaeru.ui.common.downloads

import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * What one episode row on «Загрузки» says it is doing.
 *
 * The rule under all of it: a row says the one thing its size cannot. A finished download has
 * nothing to add, and everything else is either waiting for something nameable or has failed for a
 * reason worth naming.
 */
class DownloadStateLineTest {
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")

    private fun row(state: DownloadState, progress: Float = 0f, failure: String? = null) = EpisodeDownload(
        key = DownloadKey(1, 7, translationId = 11, quality = Quality.P720),
        state = state,
        bytes = 320L * 1024 * 1024,
        progress = progress,
        failure = failure,
        updatedAt = now,
    )

    @Test
    fun `an episode on the device says nothing beyond its size`() {
        assertNull(downloadStateLine(row(DownloadState.COMPLETED, progress = 1f)))
    }

    @Test
    fun `a running download carries the same number the ring draws`() {
        assertEquals("Загружается, 42 %", downloadStateLine(row(DownloadState.DOWNLOADING, 0.42f)))
    }

    @Test
    fun `the share is floored, so a line never claims a percent that has not finished`() {
        assertEquals("Загружается, 99 %", downloadStateLine(row(DownloadState.DOWNLOADING, 0.999f)))
    }

    @Test
    fun `a resolve reads as the queue it is part of, not as a step of its own`() {
        assertEquals("В очереди", downloadStateLine(row(DownloadState.QUEUED)))
        assertEquals("В очереди", downloadStateLine(row(DownloadState.RESOLVING)))
    }

    @Test
    fun `waiting for Wi-Fi says what it is waiting for`() {
        assertEquals("Ждём Wi-Fi", downloadStateLine(row(DownloadState.WAITING_FOR_WIFI)))
    }

    @Test
    fun `a failure names its cause, because the viewer has to choose what to do about it`() {
        assertEquals("Ошибка: нет места", downloadStateLine(row(DownloadState.FAILED, failure = "нет места")))
    }

    @Test
    fun `a failure with nothing to say still says it failed`() {
        assertEquals("Ошибка", downloadStateLine(row(DownloadState.FAILED)))
    }

    @Test
    fun `a row on its way out says so, rather than looking like one that stayed`() {
        assertEquals("Удаляем", downloadStateLine(row(DownloadState.REMOVING)))
    }
}
