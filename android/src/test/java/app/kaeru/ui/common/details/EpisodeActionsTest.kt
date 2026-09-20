package app.kaeru.ui.common.details

import app.kaeru.domain.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a long press on one episode offers, and — just as much — what it does not. */
class EpisodeActionsTest {

    private fun cell(
        number: Int = 3,
        watched: Boolean = false,
        aired: Boolean = true,
        download: DownloadState? = null,
    ) = EpisodeCell(number = number, watched = watched, progress = null, aired = aired, download = download)

    @Test
    fun `an ordinary unwatched episode can be downloaded, played or counted`() {
        assertEquals(
            listOf(EpisodeAction.DOWNLOAD, EpisodeAction.WATCH, EpisodeAction.MARK_WATCHED),
            episodeActions(cell()),
        )
    }

    @Test
    fun `a watched episode can have the mark taken off it instead`() {
        assertEquals(
            listOf(EpisodeAction.DOWNLOAD, EpisodeAction.WATCH, EpisodeAction.MARK_UNWATCHED),
            episodeActions(cell(watched = true)),
        )
    }

    @Test
    fun `an unwatched episode is never offered the un-mark`() {
        assertFalse(EpisodeAction.MARK_UNWATCHED in episodeActions(cell()))
    }

    @Test
    fun `the two marks are never offered together`() {
        val both = listOf(EpisodeAction.MARK_WATCHED, EpisodeAction.MARK_UNWATCHED)
        listOf(cell(watched = true), cell(watched = false)).forEach { cell ->
            assertEquals(1, episodeActions(cell).count { it in both })
        }
    }

    @Test
    fun `an episode on the device is offered the space back rather than a second copy`() {
        assertEquals(
            listOf(EpisodeAction.REMOVE_DOWNLOAD, EpisodeAction.WATCH, EpisodeAction.MARK_WATCHED),
            episodeActions(cell(download = DownloadState.COMPLETED)),
        )
    }

    @Test
    fun `a failed download is something to ask for again`() {
        assertTrue(EpisodeAction.DOWNLOAD in episodeActions(cell(download = DownloadState.FAILED)))
    }

    @Test
    fun `an episode being removed offers nothing about downloads at all`() {
        val actions = episodeActions(cell(download = DownloadState.REMOVING))

        assertEquals(listOf(EpisodeAction.WATCH, EpisodeAction.MARK_WATCHED), actions)
    }

    @Test
    fun `an episode that has not aired has nothing to offer`() {
        assertEquals(emptyList<EpisodeAction>(), episodeActions(cell(aired = false)))
    }
}
