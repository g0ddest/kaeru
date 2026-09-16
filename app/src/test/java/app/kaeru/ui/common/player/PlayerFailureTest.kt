package app.kaeru.ui.common.player

import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

private const val OFFLINE = "Нет сети. Скачайте серию заранее"
private const val BROKEN = "Не удалось воспроизвести скачанную серию. Удалите загрузку и скачайте заново"

/**
 * What the surface over a failed video says.
 *
 * The distinction it exists for: an episode on the device plays with no network at all, so a
 * network and a failure together mean the file is the problem — and telling that viewer to
 * download the episode would be telling them to do what they have already done.
 */
class PlayerFailureTest {
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")

    private fun download(state: DownloadState) = EpisodeDownload(
        key = DownloadKey(1, 7, translationId = 11, quality = Quality.P720),
        state = state,
        bytes = 320L * 1024 * 1024,
        progress = if (state == DownloadState.COMPLETED) 1f else 0.4f,
        failure = null,
        updatedAt = now,
    )

    @Test
    fun `nothing wrong is nothing to show`() {
        assertNull(playerFailure(PlayerUiState()))
        assertNull(playerFailure(PlayerUiState(download = download(DownloadState.COMPLETED))))
        assertNull(playerFailure(PlayerUiState(failedReadingDownload = true)))
    }

    @Test
    fun `a decoder that could not read the download blames the file, not the network`() {
        val failure = playerFailure(
            PlayerUiState(
                errorMessage = "Не удалось воспроизвести",
                offline = false,
                download = download(DownloadState.COMPLETED),
                failedReadingDownload = true,
            ),
        )

        assertEquals(BROKEN, failure?.message)
        assertEquals(PlayerRecovery.REMOVE_DOWNLOAD, failure?.recovery)
    }

    /**
     * The two paths that put a downloaded episode on the source: a voice the download was not
     * fetched in, and a Chromecast, which never reads this device's cache at all. Both fail as
     * source failures, with the file sitting there perfectly playable — and the escape this branch
     * offers would delete it.
     */
    @Test
    fun `a voice switch that failed is the source's fault, downloaded or not`() {
        val failure = playerFailure(
            PlayerUiState(
                errorMessage = "Источник не отвечает",
                offline = false,
                download = download(DownloadState.COMPLETED),
                failedReadingDownload = false,
            ),
        )

        assertEquals("Источник не отвечает", failure?.message)
        assertEquals(PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
    }

    @Test
    fun `a cast failure on a downloaded episode says nothing about the download`() {
        val failure = playerFailure(
            PlayerUiState(
                errorMessage = "Не удалось начать трансляцию",
                offline = false,
                isCasting = true,
                download = download(DownloadState.COMPLETED),
                failedReadingDownload = false,
            ),
        )

        assertEquals("Не удалось начать трансляцию", failure?.message)
        assertEquals(PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
    }

    @Test
    fun `the same episode failing with no network keeps the offline wording`() {
        val failure = playerFailure(
            PlayerUiState(
                errorMessage = OFFLINE,
                offline = true,
                download = download(DownloadState.COMPLETED),
                failedReadingDownload = true,
            ),
        )

        assertEquals(OFFLINE, failure?.message)
        assertEquals(PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
    }

    @Test
    fun `an episode with no download says whatever the failure said`() {
        val failure = playerFailure(PlayerUiState(errorMessage = "Источник не отвечает", offline = false))

        assertEquals("Источник не отвечает", failure?.message)
        assertEquals(PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
    }

    @Test
    fun `a download still running has nothing playable behind it, so nothing to delete`() {
        listOf(
            DownloadState.QUEUED,
            DownloadState.RESOLVING,
            DownloadState.DOWNLOADING,
            DownloadState.WAITING_FOR_WIFI,
            DownloadState.FAILED,
            DownloadState.REMOVING,
        ).forEach { state ->
            val failure = playerFailure(
                PlayerUiState(errorMessage = "Источник не отвечает", offline = false, download = download(state)),
            )

            assertEquals(state.name, "Источник не отвечает", failure?.message)
            assertEquals(state.name, PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
        }
    }

    @Test
    fun `offline with nothing downloaded is still the plain message`() {
        val failure = playerFailure(PlayerUiState(errorMessage = OFFLINE, offline = true))

        assertEquals(OFFLINE, failure?.message)
        assertEquals(PlayerRecovery.CHANGE_TRANSLATION, failure?.recovery)
    }
}
