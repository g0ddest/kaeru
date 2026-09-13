package app.kaeru.ui.tv.player

import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.tv.player.TvPlayerFocus.ACTIONS
import app.kaeru.ui.tv.player.TvPlayerFocus.NONE
import app.kaeru.ui.tv.player.TvPlayerFocus.PROGRESS
import app.kaeru.ui.tv.player.TvPlayerFocus.STRIP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The whole remote, as a table. Every rule the D-pad obeys lives in [TvPlayerKeyHandler], so
 * every rule can be checked here without a television.
 */
class TvPlayerKeyHandlerTest {

    private fun press(
        key: TvKey,
        focus: TvPlayerFocus = NONE,
        repeat: Int = 0,
        panel: Boolean = focus != NONE,
    ) = TvPlayerKeyHandler.onKey(key, KeyAction.DOWN, panel, repeat, focus)

    private fun release(key: TvKey, focus: TvPlayerFocus = NONE) =
        TvPlayerKeyHandler.onKey(key, KeyAction.UP, focus != NONE, 0, focus)

    // --- seeking ---------------------------------------------------------------------------

    @Test
    fun `left and right seek ten seconds while the panel is down`() {
        assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.LEFT))
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.RIGHT))
    }

    @Test
    fun `left and right seek while the timeline holds focus`() {
        assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.LEFT, PROGRESS))
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.RIGHT, PROGRESS))
    }

    @Test
    fun `holding right widens the step to half a minute, then to a minute`() {
        assertEquals(TvPlayerCommand.SeekBy(10_000), press(TvKey.RIGHT, repeat = 2))
        assertEquals(TvPlayerCommand.SeekBy(30_000), press(TvKey.RIGHT, repeat = 3))
        assertEquals(TvPlayerCommand.SeekBy(30_000), press(TvKey.RIGHT, repeat = 7))
        assertEquals(TvPlayerCommand.SeekBy(60_000), press(TvKey.RIGHT, repeat = 8))
        assertEquals(TvPlayerCommand.SeekBy(60_000), press(TvKey.RIGHT, repeat = 40))
    }

    @Test
    fun `holding left accelerates backwards by the same steps`() {
        assertEquals(TvPlayerCommand.SeekBy(-10_000), press(TvKey.LEFT, repeat = 2))
        assertEquals(TvPlayerCommand.SeekBy(-30_000), press(TvKey.LEFT, PROGRESS, repeat = 4))
        assertEquals(TvPlayerCommand.SeekBy(-60_000), press(TvKey.LEFT, PROGRESS, repeat = 9))
    }

    @Test
    fun `left and right belong to the buttons once the action row has focus`() {
        assertNull(press(TvKey.LEFT, ACTIONS))
        assertNull(press(TvKey.RIGHT, ACTIONS, repeat = 5))
    }

    @Test
    fun `an open strip keeps the whole D-pad to itself`() {
        assertNull(press(TvKey.LEFT, STRIP))
        assertNull(press(TvKey.RIGHT, STRIP))
        assertNull(press(TvKey.UP, STRIP))
        assertNull(press(TvKey.DOWN, STRIP))
        assertNull(press(TvKey.CENTER, STRIP))
    }

    // --- the vertical axis -----------------------------------------------------------------

    @Test
    fun `up opens the episodes strip from the video and from the timeline`() {
        assertEquals(TvPlayerCommand.OpenEpisodes, press(TvKey.UP))
        assertEquals(TvPlayerCommand.OpenEpisodes, press(TvKey.UP, PROGRESS))
    }

    @Test
    fun `up from the action row climbs back to the timeline`() {
        assertEquals(TvPlayerCommand.FocusProgress, press(TvKey.UP, ACTIONS))
    }

    @Test
    fun `down opens quality from the video and from the action row`() {
        assertEquals(TvPlayerCommand.OpenQuality, press(TvKey.DOWN))
        assertEquals(TvPlayerCommand.OpenQuality, press(TvKey.DOWN, ACTIONS))
    }

    @Test
    fun `down from the timeline drops into the action row`() {
        assertEquals(TvPlayerCommand.FocusActions, press(TvKey.DOWN, PROGRESS))
    }

    // --- the centre ------------------------------------------------------------------------

    @Test
    fun `the centre pauses and resumes while nothing else is focused`() {
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.CENTER))
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.CENTER, PROGRESS))
    }

    @Test
    fun `the centre presses the focused button rather than the video`() {
        assertNull(press(TvKey.CENTER, ACTIONS))
    }

    // --- back ------------------------------------------------------------------------------

    @Test
    fun `back hides the panel first and leaves only once it is down`() {
        assertEquals(TvPlayerCommand.HidePanel, press(TvKey.BACK, PROGRESS))
        assertEquals(TvPlayerCommand.HidePanel, press(TvKey.BACK, ACTIONS))
        assertEquals(TvPlayerCommand.Exit, press(TvKey.BACK))
    }

    @Test
    fun `back closes an open strip before it touches the panel`() {
        assertEquals(TvPlayerCommand.CloseStrip, press(TvKey.BACK, STRIP))
    }

    @Test
    fun `a panel that is up without focus still swallows the first back`() {
        assertEquals(TvPlayerCommand.HidePanel, press(TvKey.BACK, NONE, panel = true))
    }

    // --- media keys ------------------------------------------------------------------------

    @Test
    fun `the media keys work wherever focus happens to be`() {
        for (focus in TvPlayerFocus.entries) {
            assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.MEDIA_PLAY_PAUSE, focus))
            assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.MEDIA_FAST_FORWARD, focus))
            assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.MEDIA_REWIND, focus))
            assertEquals(TvPlayerCommand.PlayNext, press(TvKey.MEDIA_NEXT, focus))
        }
    }

    @Test
    fun `holding fast forward accelerates like the D-pad does`() {
        assertEquals(TvPlayerCommand.SeekBy(30_000), press(TvKey.MEDIA_FAST_FORWARD, repeat = 3))
        assertEquals(TvPlayerCommand.SeekBy(-60_000), press(TvKey.MEDIA_REWIND, ACTIONS, repeat = 8))
    }

    // --- everything else -------------------------------------------------------------------

    @Test
    fun `a key with nothing to do only wakes the panel`() {
        assertEquals(TvPlayerCommand.ShowPanel, press(TvKey.OTHER))
        assertNull(press(TvKey.OTHER, PROGRESS))
        assertNull(press(TvKey.OTHER, ACTIONS))
        assertNull(press(TvKey.OTHER, STRIP))
    }

    @Test
    fun `releasing a key commands nothing`() {
        for (key in TvKey.entries) {
            assertNull("$key on release", release(key))
            assertNull("$key on release over the timeline", release(key, PROGRESS))
            assertNull("$key on release over the buttons", release(key, ACTIONS))
        }
    }

    @Test
    fun `focus reported without a panel is not believed`() {
        // The panel hid on its own between the key press and the report; the video is in charge.
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.RIGHT, ACTIONS, panel = false))
        assertEquals(TvPlayerCommand.Exit, press(TvKey.BACK, STRIP, panel = false))
    }
}
