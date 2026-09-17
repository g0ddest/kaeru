package app.kaeru.ui.tv.player

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the two strips over the video actually list, and what each chip says about itself.
 *
 * Both answers are pure functions rather than composition, because both are rules and not
 * drawing: which episodes can be started at all, and how a chip marks the one in play against
 * the one the viewer keeps coming back to.
 */
class TvPlayerStripTest {

    private fun cell(number: Int, aired: Boolean = true, progress: Float? = null, watched: Boolean = false) =
        EpisodeCell(number = number, watched = watched, progress = progress, aired = aired)

    private fun ranked(id: Int, title: String, kind: TranslationKind, oftenChosen: Boolean = false) =
        RankedTranslation(Translation(id, title, kind, episodesCount = 12), oftenChosen)

    // --- the episodes strip -------------------------------------------------------------------

    @Test
    fun `the strip lists what has aired and leaves out what has not`() {
        // The title screen draws an unaired episode as waiting. This strip is a way to change
        // what is playing, so an episode nobody can start has no chip at all.
        val state = PlayerUiState(
            episodes = listOf(cell(1), cell(2), cell(3), cell(4, aired = false), cell(5, aired = false)),
        )

        assertEquals(listOf(1, 2, 3), tvAiredEpisodes(state).map { it.number })
    }

    @Test
    fun `each chip carries the position that episode was left at`() {
        val state = PlayerUiState(
            episodes = listOf(cell(1, watched = true, progress = 1f), cell(2, progress = 0.42f), cell(3)),
        )
        val strip = tvAiredEpisodes(state)

        assertEquals(listOf(1f, 0.42f, null), strip.map { it.progress })
        assertEquals(listOf(true, false, false), strip.map { it.watched })
    }

    @Test
    fun `a season nobody has a catalogue entry for has no strip`() {
        assertEquals(emptyList<EpisodeCell>(), tvAiredEpisodes(PlayerUiState()))
    }

    // --- where the strip stands -----------------------------------------------------------------

    private fun placement(cells: List<EpisodeCell>, playing: Int) =
        tvStripPlacement(cells, key = { it.number }, isCurrent = { it.number == playing })

    @Test
    fun `a progress sample leaves the strip's placement alone`() {
        // The one value the row keys its anchor and its opening scroll on. The episode in play
        // has its position rewritten every few seconds while it runs, so a placement that
        // noticed would yank the row back under the viewer's thumb mid-browse and forget where
        // they had got to — which is exactly what keying on the cells used to do.
        val ticking = listOf(cell(1, watched = true), cell(2, progress = 0.31f), cell(3))
        val ticked = listOf(cell(1, watched = true), cell(2, progress = 0.34f), cell(3))

        assertNotEquals(ticking, ticked)
        assertEquals(placement(ticking, playing = 2), placement(ticked, playing = 2))
    }

    @Test
    fun `an episode airing does change it`() {
        val before = listOf(cell(1), cell(2))
        val after = listOf(cell(1), cell(2), cell(3))

        assertNotEquals(placement(before, playing = 2), placement(after, playing = 2))
    }

    @Test
    fun `moving on to the next episode does change it`() {
        val cells = listOf(cell(1), cell(2), cell(3))

        assertNotEquals(placement(cells, playing = 2), placement(cells, playing = 3))
    }

    @Test
    fun `the row opens two chips before the one in play`() {
        val cells = (1..40).map { cell(it) }

        assertEquals(0, placement(cells, playing = 1).firstVisible)
        assertEquals(0, placement(cells, playing = 3).firstVisible)
        assertEquals(1, placement(cells, playing = 4).firstVisible)
        assertEquals(18, placement(cells, playing = 21).firstVisible)
    }

    @Test
    fun `a strip whose chip in play is missing starts at the beginning`() {
        // Picking an episode is answered by the controller, so for a moment the number the strip
        // is marking is one the list does not carry yet.
        val cells = listOf(cell(1), cell(2))
        val nowhere = placement(cells, playing = 9)

        assertEquals(0, nowhere.firstVisible)
        assertEquals(1, nowhere.current)
    }

    // --- the voices strip -----------------------------------------------------------------------

    @Test
    fun `a track is named by its studio`() {
        assertEquals("AniLibria", translationName(ranked(1, "AniLibria", TranslationKind.VOICE)))
    }

    @Test
    fun `subtitles say so, since the remote cannot hear the difference`() {
        assertEquals(
            "Crunchyroll (субтитры)",
            translationName(ranked(1, "Crunchyroll", TranslationKind.SUBTITLES)),
        )
    }

    @Test
    fun `the track that is playing is the one marked chosen`() {
        val track = ranked(7, "AniLibria", TranslationKind.VOICE)

        assertEquals("Выбрано", translationCaption(track, currentId = 7))
        assertNull(translationCaption(track, currentId = 3))
    }

    @Test
    fun `a track the viewer keeps choosing says so under its name`() {
        val habit = ranked(7, "Studio Band", TranslationKind.VOICE, oftenChosen = true)

        assertEquals("Часто выбираете", translationCaption(habit, currentId = null))
    }

    @Test
    fun `a track without the episode on screen says so, whatever else it is`() {
        val behind = ranked(7, "Studio Band", TranslationKind.VOICE, oftenChosen = true).copy(hasEpisode = false)

        assertEquals("нет серии 5", translationCaption(behind, currentId = 7, episode = 5))
        assertEquals("нет серии 5", translationCaption(behind, currentId = null, episode = 5))
    }

    @Test
    fun `a track that has the episode, or is not known not to, is captioned as before`() {
        val has = ranked(7, "AniLibria", TranslationKind.VOICE).copy(hasEpisode = true)
        val unknown = ranked(8, "AniDUB", TranslationKind.VOICE)

        assertEquals("Выбрано", translationCaption(has, currentId = 7, episode = 5))
        assertNull(translationCaption(unknown, currentId = 7, episode = 5))
    }

    @Test
    fun `the track in play says only that, however often it is chosen`() {
        // Two marks on one chip say less than one, which is the same argument the ranking itself
        // makes: a track already chosen here does not also need to be recommended.
        val both = ranked(7, "Studio Band", TranslationKind.VOICE, oftenChosen = true)

        assertEquals("Выбрано", translationCaption(both, currentId = 7))
    }
}
