package app.kaeru.ui.tv.player

import app.kaeru.player.EpisodeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole remote, as a table. Every rule the D-pad obeys lives in [TvPlayerKeyHandler], so
 * every rule can be checked here without a television.
 *
 * Two vocabularies meet in this function. While the panel is down the remote drives the video:
 * left and right scrub, the centre pauses, up and down bring the controls back. While the panel
 * is up the remote drives the panel: up and down walk its rungs and everything else belongs to
 * the row that has focus. The media keys are the same buttons either way, which is what makes
 * them media keys.
 */
class TvPlayerKeyHandlerTest {

    private fun press(
        key: TvKey,
        panel: Boolean = false,
        playing: Boolean = true,
        card: Boolean = false,
        skip: Boolean = false,
        repeat: Int = 0,
    ) = TvPlayerKeyHandler.onKey(key, KeyAction.DOWN, panel, playing, card, skip, repeat)

    private fun release(key: TvKey, panel: Boolean = false) =
        TvPlayerKeyHandler.onKey(key, KeyAction.UP, panel, isPlaying = true)

    // --- the video, with the controls out of the way -----------------------------------------

    @Test
    fun `left and right seek ten seconds while the panel is down`() {
        assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.LEFT))
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.RIGHT))
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
        assertEquals(TvPlayerCommand.SeekBy(-30_000), press(TvKey.LEFT, repeat = 4))
        assertEquals(TvPlayerCommand.SeekBy(-60_000), press(TvKey.LEFT, repeat = 9))
    }

    @Test
    fun `the centre pauses the picture while the panel is down`() {
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.CENTER))
    }

    @Test
    fun `up asks for the panel at the episodes strip and down at the quality strip`() {
        assertEquals(TvPlayerCommand.ShowPanel(TvPanelRung.EPISODES), press(TvKey.UP))
        assertEquals(TvPlayerCommand.ShowPanel(TvPanelRung.QUALITY), press(TvKey.DOWN))
    }

    @Test
    fun `a key with nothing to do brings the panel back where it was left`() {
        assertEquals(TvPlayerCommand.ShowPanel(null), press(TvKey.OTHER))
    }

    // --- the panel ----------------------------------------------------------------------------

    @Test
    fun `up and down walk the rungs once the panel is up`() {
        assertEquals(TvPlayerCommand.MoveRung(down = false), press(TvKey.UP, panel = true))
        assertEquals(TvPlayerCommand.MoveRung(down = true), press(TvKey.DOWN, panel = true))
    }

    @Test
    fun `left, right and the centre belong to the focused row`() {
        assertNull(press(TvKey.LEFT, panel = true))
        assertNull(press(TvKey.RIGHT, panel = true, repeat = 6))
        assertNull(press(TvKey.CENTER, panel = true))
        assertNull(press(TvKey.OTHER, panel = true))
    }

    // --- back -----------------------------------------------------------------------------------

    @Test
    fun `back takes the panel down first and leaves only once it is down`() {
        assertEquals(TvPlayerCommand.HidePanel, press(TvKey.BACK, panel = true))
        assertEquals(TvPlayerCommand.Exit, press(TvKey.BACK))
    }

    // --- the media keys ---------------------------------------------------------------------

    @Test
    fun `play pause toggles wherever the panel is and whatever is playing`() {
        for (panel in listOf(false, true)) {
            for (playing in listOf(false, true)) {
                assertEquals(
                    "panel=$panel playing=$playing",
                    TvPlayerCommand.TogglePlayPause,
                    press(TvKey.MEDIA_PLAY_PAUSE, panel = panel, playing = playing),
                )
            }
        }
    }

    @Test
    fun `a remote with separate play and pause keys never fights the state it finds`() {
        // Two dedicated keys are not a toggle: play on a running episode has to do nothing, or
        // a viewer pressing it twice stops what they asked to start.
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.MEDIA_PLAY, playing = false))
        assertNull(press(TvKey.MEDIA_PLAY, playing = true))
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.MEDIA_PAUSE, playing = true))
        assertNull(press(TvKey.MEDIA_PAUSE, playing = false))
    }

    @Test
    fun `rewind and fast forward seek and accelerate like the D-pad does`() {
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.MEDIA_FAST_FORWARD))
        assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.MEDIA_REWIND))
        assertEquals(TvPlayerCommand.SeekBy(30_000), press(TvKey.MEDIA_FAST_FORWARD, repeat = 3))
        assertEquals(TvPlayerCommand.SeekBy(-60_000), press(TvKey.MEDIA_REWIND, repeat = 8))
    }

    @Test
    fun `next moves on and stop leaves with the position saved`() {
        assertEquals(TvPlayerCommand.PlayNext, press(TvKey.MEDIA_NEXT))
        assertEquals(TvPlayerCommand.Exit, press(TvKey.MEDIA_STOP))
    }

    @Test
    fun `the media keys still work while a card holds the D-pad`() {
        assertEquals(TvPlayerCommand.TogglePlayPause, press(TvKey.MEDIA_PLAY_PAUSE, card = true))
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.MEDIA_FAST_FORWARD, card = true))
        assertEquals(TvPlayerCommand.PlayNext, press(TvKey.MEDIA_NEXT, card = true))
        assertEquals(TvPlayerCommand.Exit, press(TvKey.MEDIA_STOP, card = true))
    }

    // --- a card on screen -----------------------------------------------------------------------

    @Test
    fun `a card lets its own buttons answer left, right and the centre`() {
        for (panel in listOf(false, true)) {
            for (key in listOf(TvKey.LEFT, TvKey.RIGHT, TvKey.CENTER, TvKey.OTHER)) {
                assertNull("$key over a card, panel=$panel", press(key, panel = panel, card = true))
            }
        }
    }

    @Test
    fun `a card swallows up and down rather than letting focus walk out of it`() {
        // The autoplay offer stands above the panel, and Compose would happily walk a press down
        // into the strips behind it — leaving the viewer on a control the card is covering.
        for (panel in listOf(false, true)) {
            assertEquals(TvPlayerCommand.KeepFocus, press(TvKey.UP, panel = panel, card = true))
            assertEquals(TvPlayerCommand.KeepFocus, press(TvKey.DOWN, panel = panel, card = true))
        }
    }

    // --- the skip button holding the focus ------------------------------------------------------

    @Test
    fun `the skip button takes the centre and leaves the rest of the remote alone`() {
        // Ten seconds of a television that cannot scrub is a worse trade than a shortcut nobody
        // presses, so left and right still jog and up and down still bring the panel back. Moving
        // the focus off the button is the viewer's to make; the button stays up either way.
        assertEquals(TvPlayerCommand.SeekBy(-EpisodeQueue.SEEK_STEP_MS), press(TvKey.LEFT, skip = true))
        assertEquals(TvPlayerCommand.SeekBy(EpisodeQueue.SEEK_STEP_MS), press(TvKey.RIGHT, skip = true))
        assertEquals(TvPlayerCommand.ShowPanel(TvPanelRung.EPISODES), press(TvKey.UP, skip = true))
        assertEquals(TvPlayerCommand.ShowPanel(TvPanelRung.QUALITY), press(TvKey.DOWN, skip = true))
        // The one key that changes hands: OK belongs to the focused button, and a handler that
        // read it as «pause the picture» would make the shortcut cost two presses — or, on the
        // ending, pause instead of moving on and move on instead of pausing.
        assertNull(press(TvKey.CENTER, skip = true))
    }

    @Test
    fun `back still leaves the player while the button is up`() {
        assertEquals(TvPlayerCommand.Exit, press(TvKey.BACK, skip = true))
    }

    @Test
    fun `a card outranks the button, and the panel answers for its own row`() {
        // A question on screen still owns the D-pad, button or no button.
        assertEquals(TvPlayerCommand.KeepFocus, press(TvKey.UP, card = true, skip = true))
        assertNull(press(TvKey.CENTER, card = true, skip = true))
        // And with the controls up, up and down walk the rungs as they always do.
        assertEquals(TvPlayerCommand.MoveRung(down = false), press(TvKey.UP, panel = true, skip = true))
        assertNull(press(TvKey.LEFT, panel = true, skip = true))
    }

    // --- releases ---------------------------------------------------------------------------

    @Test
    fun `releasing a key commands nothing`() {
        for (key in TvKey.entries) {
            assertNull("$key on release", release(key))
            assertNull("$key on release with the panel up", release(key, panel = true))
        }
    }

    // --- what wakes the panel -----------------------------------------------------------------

    @Test
    fun `scrubbing keeps the picture clear and every other key brings the panel back`() {
        for (key in listOf(TvKey.LEFT, TvKey.RIGHT, TvKey.MEDIA_REWIND, TvKey.MEDIA_FAST_FORWARD)) {
            assertFalse("$key should scrub without raising the panel", wakesPanel(key, panelVisible = false))
        }
        for (key in listOf(TvKey.UP, TvKey.DOWN, TvKey.CENTER, TvKey.OTHER, TvKey.MEDIA_PLAY_PAUSE)) {
            assertTrue("$key should raise the panel", wakesPanel(key, panelVisible = false))
        }
    }

    @Test
    fun `walking a row keeps the panel up`() {
        // Left and right mean «next chip» once the panel is up, and a chosen chip that let the
        // panel time out under the viewer's thumb would be the panel's worst moment to leave.
        for (key in TvKey.entries) {
            assertTrue("$key with the panel up", wakesPanel(key, panelVisible = true))
        }
    }
}
