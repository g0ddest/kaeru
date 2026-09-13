package app.kaeru.ui.tv.player

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState
import org.junit.Assert.assertEquals
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
    fun `the track in play says only that, however often it is chosen`() {
        // Two marks on one chip say less than one, which is the same argument the ranking itself
        // makes: a track already chosen here does not also need to be recommended.
        val both = ranked(7, "Studio Band", TranslationKind.VOICE, oftenChosen = true)

        assertEquals("Выбрано", translationCaption(both, currentId = 7))
    }
}
