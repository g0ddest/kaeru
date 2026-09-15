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
        nextEpisodeAvailable: Boolean = false,
    ) = PlayerUiState(
        episodes = episodes, translations = translations, qualities = qualities,
        nextEpisodeAvailable = nextEpisodeAvailable,
    )

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

    // --- what the content carries over from the state -------------------------------------------

    @Test
    fun `the panel's answer about a next episode is the state's, not its own default`() {
        // `TvPanelContent` exists to stop a position tick rebuilding the panel four times a
        // second, so every field on it is a copy — and a copy defaulted to `false` that nobody
        // assigns is the shape that quietly loses the «Следующая» button on the transport row
        // while the rest of the suite stays green.
        assertTrue(tvPanelContent(state(nextEpisodeAvailable = true)).nextEpisodeAvailable)
        assertFalse(tvPanelContent(state(nextEpisodeAvailable = false)).nextEpisodeAvailable)
    }

    @Test
    fun `the episode and the chosen voice come over with it`() {
        val full = PlayerUiState(episode = 7, translations = listOf(track(3)), translationId = 3)
        val content = tvPanelContent(full)

        assertEquals(7, content.episode)
        assertEquals(3, content.translationId)
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
    fun `a step from a rung whose strip is gone moves, rather than landing back on the clamp`() {
        // The panel parked on the voices while their list loads: there is no voices strip, so the
        // D-pad stands one rung down, on the quality row. The step used to be measured from the
        // remembered rung, find it missing and answer with that same clamp — so the first press of
        // every pair moved nothing and the viewer pressed everything twice.
        val rungs = listOf(EPISODES, QUALITY, TRANSPORT)

        assertEquals(TRANSPORT, tvStepRung(rungs, TRANSLATIONS, down = true))
        assertEquals(EPISODES, tvStepRung(rungs, TRANSLATIONS, down = false))
    }

    @Test
    fun `a rung that has gone away hands the D-pad to the next one down`() {
        val rungs = listOf(QUALITY, TRANSPORT)

        assertEquals(QUALITY, tvRungOrNearest(rungs, EPISODES))
        assertEquals(QUALITY, tvRungOrNearest(rungs, TRANSLATIONS))
        assertEquals(QUALITY, tvRungOrNearest(rungs, QUALITY))
    }

    @Test
    fun `a rung the axis skips over hands the D-pad to the transport row`() {
        assertEquals(TRANSPORT, tvRungOrNearest(listOf(EPISODES, TRANSPORT), QUALITY))
    }

    @Test
    fun `asking a panel with no rungs at all answers the transport row`() {
        // Unreachable through `tvPanelRungs`, which always carries the transport row. It is here
        // so the function is total: an answer, rather than an exception, if it ever is.
        assertEquals(TRANSPORT, tvRungOrNearest(emptyList(), EPISODES))
    }

    // --- the failure, and stepping aside for the voices ------------------------------------------

    private fun broken(
        translations: List<RankedTranslation> = emptyList(),
        loading: Boolean = false,
    ) = PlayerUiState(
        errorMessage = "Источник временно недоступен",
        translations = translations,
        loadingTranslations = loading,
    )

    @Test
    fun `the failure owns the screen until the viewer asks for another voice`() {
        assertTrue(tvShowsFailure(broken(), choosingTrack = false))
        assertTrue(tvShowsFailure(broken(listOf(track(1))), choosingTrack = false))
    }

    @Test
    fun `it steps aside while the voices are on their way and once they arrive`() {
        assertFalse(tvShowsFailure(broken(loading = true), choosingTrack = true))
        assertFalse(tvShowsFailure(broken(listOf(track(1))), choosingTrack = true))
    }

    @Test
    fun `a load that brings back nothing puts the failure back`() {
        // Otherwise the panel stands over a dead picture with no voices row and «Повторить»
        // nowhere on screen: back hides the panel, back again leaves the player.
        assertTrue(tvShowsFailure(broken(), choosingTrack = true))
    }

    @Test
    fun `nothing steps aside when nothing failed`() {
        assertFalse(tvShowsFailure(PlayerUiState(), choosingTrack = true))
        assertFalse(tvShowsFailure(PlayerUiState(), choosingTrack = false))
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

    // --- what a key press leaves the panel standing on -----------------------------------------

    @Test
    fun `a wake that names no rung leaves the panel on the one it was left on`() {
        val panel = TvPanel(visible = false, rung = TvPanelRung.TRANSLATIONS)

        val woken = tvPanelAfterWake(panel, TvPlayerCommand.SeekBy(10_000), atMs = 1_000)

        assertTrue(woken.visible)
        assertEquals(TvPanelRung.TRANSLATIONS, woken.rung)
    }

    @Test
    fun `a wake that names a rung stands on it`() {
        val panel = TvPanel(visible = false, rung = TvPanelRung.TRANSPORT)

        val woken = tvPanelAfterWake(panel, TvPlayerCommand.ShowPanel(TvPanelRung.EPISODES), atMs = 1_000)

        assertEquals(TvPanelRung.EPISODES, woken.rung)
    }

    @Test
    fun `a wake keeps a rung whose strip has not arrived yet`() {
        // The fault this guards: «Сменить озвучку» parks the panel on the voices while the list is
        // still loading, so that strip does not exist. Pressing anything used to resolve the rung
        // against the strips there were and write the answer back — the D-pad settled on the
        // quality row and was still there when the voices landed.
        val asked = TvPanel(visible = true, rung = TvPanelRung.TRANSLATIONS)
        val withoutVoices = listOf(TvPanelRung.EPISODES, TvPanelRung.QUALITY, TvPanelRung.TRANSPORT)

        val woken = tvPanelAfterWake(asked, command = null, atMs = 1_000)

        assertEquals(TvPanelRung.TRANSLATIONS, woken.rung)
        // Meanwhile the D-pad stands somewhere it can: the clamp lives at the point of asking.
        assertEquals(TvPanelRung.QUALITY, tvRungOrNearest(withoutVoices, woken.rung))
    }
}
