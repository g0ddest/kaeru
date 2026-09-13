package app.kaeru.ui.tv.player

import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.tv.player.TvPanelRung.EPISODES
import app.kaeru.ui.tv.player.TvPanelRung.QUALITY
import app.kaeru.ui.tv.player.TvPanelRung.TRANSLATIONS
import app.kaeru.ui.tv.player.TvPanelRung.TRANSPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel as a value: whether it is on screen, which rung the D-pad stands on, and when it is
 * allowed to take itself down. All of it decided here rather than in the composition, so a
 * television is not needed to find out what a press does.
 */
class TvPanelTest {

    private fun state(
        episodes: List<EpisodeCell> = emptyList(),
        translations: List<RankedTranslation> = emptyList(),
        qualities: List<Quality> = emptyList(),
    ) = PlayerUiState(episodes = episodes, translations = translations, qualities = qualities)

    private fun cell(number: Int, aired: Boolean = true, progress: Float? = null, watched: Boolean = false) =
        EpisodeCell(number = number, watched = watched, progress = progress, aired = aired)

    private fun track(id: Int, oftenChosen: Boolean = false) =
        RankedTranslation(Translation(id, "Studio $id", TranslationKind.VOICE, 12), oftenChosen)

    // --- showing and hiding -------------------------------------------------------------------

    @Test
    fun `a press puts the panel up on the rung it asks for`() {
        val panel = TvPanel().shown(EPISODES, atMs = 1_000)

        assertTrue(panel.visible)
        assertEquals(EPISODES, panel.rung)
    }

    @Test
    fun `the panel comes back where it was left`() {
        val browsing = TvPanel().shown(TRANSLATIONS, atMs = 1_000)
        val away = browsing.hidden()

        assertFalse(away.visible)
        assertEquals(TRANSLATIONS, away.rung)
        assertEquals(TRANSLATIONS, away.shown(atMs = 5_000).rung)
    }

    @Test
    fun `a press restarts the linger timer and becomes the reference point`() {
        val panel = TvPanel().shown(atMs = 1_000)

        assertEquals(1, panel.wake)
        assertEquals(1_000L, panel.lastWakeMs)
    }

    @Test
    fun `a held button restarts the timer at most twice a second`() {
        val first = TvPanel().shown(atMs = 1_000)
        val tooSoon = first.shown(atMs = 1_400)
        val farEnough = tooSoon.shown(atMs = 1_500)

        assertEquals(first.wake, tooSoon.wake)
        assertEquals(1_000L, tooSoon.lastWakeMs)
        assertEquals(first.wake + 1, farEnough.wake)
        assertEquals(1_500L, farEnough.lastWakeMs)
    }

    @Test
    fun `a wake with no key behind it does not poison the throttle`() {
        // The autoplay offer asks for the panel with no press behind it. Storing a time no press
        // could ever beat is what used to leave every later press reading as «too soon», so a
        // held button stopped keeping the panel up for the rest of the session.
        val offered = TvPanel().shown(atMs = 1_000).shown(atMs = null)

        assertTrue(offered.visible)
        // The reference point stays on the last press, so the next one is measured against that
        // and not against a moment no button could ever beat.
        assertEquals(1_000L, offered.lastWakeMs)
        assertEquals(1_600L, offered.shown(atMs = 1_600).lastWakeMs)
    }

    // --- when it goes away on its own ---------------------------------------------------------

    @Test
    fun `the panel times out over a running picture`() {
        assertTrue(TvPanel().shown().hidesItself(playing = true, asking = false))
    }

    @Test
    fun `a paused picture keeps its controls`() {
        // There is nothing behind them worth looking at, and the viewer paused to read them.
        assertFalse(TvPanel().shown().hidesItself(playing = false, asking = false))
    }

    @Test
    fun `nothing hides while it is being chosen from, read or waited on`() {
        assertFalse(TvPanel().shown().hidesItself(playing = true, asking = true))
    }

    @Test
    fun `a panel already down has no timer to run`() {
        assertFalse(TvPanel().hidden().hidesItself(playing = true, asking = false))
    }

    // --- which rungs exist --------------------------------------------------------------------

    @Test
    fun `the transport row is the one rung that is always there`() {
        assertEquals(listOf(TRANSPORT), tvPanelRungs(state()))
    }

    @Test
    fun `a rung appears once it has something to choose from`() {
        val full = state(
            episodes = listOf(cell(1), cell(2)),
            translations = listOf(track(1)),
            qualities = listOf(Quality.P720),
        )

        assertEquals(listOf(EPISODES, TRANSLATIONS, QUALITY, TRANSPORT), tvPanelRungs(full))
    }

    @Test
    fun `a season with nothing aired yet is not a rung`() {
        // Every tile would be unpressable, and a rung the D-pad lands on with nothing to press
        // is a dead end on a remote.
        assertEquals(listOf(TRANSPORT), tvPanelRungs(state(episodes = listOf(cell(1, aired = false)))))
    }

    // --- walking them -------------------------------------------------------------------------

    @Test
    fun `down and up step through the rungs that exist`() {
        val rungs = listOf(EPISODES, QUALITY, TRANSPORT)

        assertEquals(QUALITY, tvStepRung(rungs, EPISODES, down = true))
        assertEquals(TRANSPORT, tvStepRung(rungs, QUALITY, down = true))
        assertEquals(QUALITY, tvStepRung(rungs, TRANSPORT, down = false))
    }

    @Test
    fun `the axis stops at both ends rather than wrapping`() {
        // Wrapping from the transport row to the episodes strip would move the viewer's eye the
        // full height of the screen for a press that means «one more down».
        val rungs = listOf(EPISODES, TRANSPORT)

        assertEquals(EPISODES, tvStepRung(rungs, EPISODES, down = false))
        assertEquals(TRANSPORT, tvStepRung(rungs, TRANSPORT, down = true))
    }

    @Test
    fun `a rung that has gone away hands the D-pad to the next one down`() {
        val rungs = listOf(QUALITY, TRANSPORT)

        assertEquals(QUALITY, tvRungOrNearest(rungs, EPISODES))
        assertEquals(QUALITY, tvRungOrNearest(rungs, TRANSLATIONS))
        assertEquals(QUALITY, tvRungOrNearest(rungs, QUALITY))
    }

    @Test
    fun `a rung below everything that exists falls back to the last one`() {
        assertEquals(TRANSPORT, tvRungOrNearest(listOf(EPISODES, TRANSPORT), QUALITY))
    }

    // --- the throttle on its own ----------------------------------------------------------------

    @Test
    fun `the wake throttle is the whole rule`() {
        assertEquals(1_000L, nextWake(atMs = 1_000, lastWakeMs = 0))
        assertNull(nextWake(atMs = 1_050, lastWakeMs = 1_000))
        assertNull(nextWake(atMs = 1_499, lastWakeMs = 1_000))
        assertEquals(1_500L, nextWake(atMs = 1_500, lastWakeMs = 1_000))
        assertEquals(4_000L, nextWake(atMs = null, lastWakeMs = 4_000))
    }
}
