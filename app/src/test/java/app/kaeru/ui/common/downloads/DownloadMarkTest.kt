package app.kaeru.ui.common.downloads

import app.kaeru.domain.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seven engine states, four things a control can say — and every surface says them the same way.
 *
 * The test exists because two surfaces used to disagree: an episode waiting for Wi-Fi wore the
 * pending arrow in the season grid and the done mark in the player's top bar, where pressing it
 * offered to delete a download that had not started.
 */
class DownloadMarkTest {

    @Test
    fun `everything that is only on its way reads as pending`() {
        listOf(DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.WAITING_FOR_WIFI)
            .forEach { state -> assertEquals(state.name, DownloadMark.PENDING, downloadMark(state)) }
    }

    @Test
    fun `a running download is the one with a fraction to draw`() {
        assertEquals(DownloadMark.RUNNING, downloadMark(DownloadState.DOWNLOADING))
    }

    @Test
    fun `only a finished one says it is on the device`() {
        assertEquals(DownloadMark.DONE, downloadMark(DownloadState.COMPLETED))
    }

    @Test
    fun `a failure is its own thing, so a surface can offer it again rather than delete it`() {
        assertEquals(DownloadMark.FAILED, downloadMark(DownloadState.FAILED))
    }

    @Test
    fun `nothing asked for and something on its way out both draw nothing`() {
        assertEquals(DownloadMark.NONE, downloadMark(null))
        assertEquals(DownloadMark.NONE, downloadMark(DownloadState.REMOVING))
    }

    @Test
    fun `every state the engine has is accounted for`() {
        DownloadState.entries.forEach { state -> downloadMark(state) }
    }
}
