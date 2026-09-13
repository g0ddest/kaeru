package app.kaeru.ui.tv.player

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a voice chip says across the room. A chip is one line with no room under it for a caption,
 * so everything the row has to say about a track has to fit the label itself.
 */
class TvTranslationLabelTest {
    private fun ranked(title: String, kind: TranslationKind, oftenChosen: Boolean) =
        RankedTranslation(Translation(1, title, kind, episodesCount = 12), oftenChosen)

    @Test
    fun `a dub is named and nothing more`() {
        assertEquals("AniLibria", translationLabel(ranked("AniLibria", TranslationKind.VOICE, false)))
    }

    @Test
    fun `subtitles say so, since the remote cannot hear the difference`() {
        assertEquals(
            "Crunchyroll (субтитры)",
            translationLabel(ranked("Crunchyroll", TranslationKind.SUBTITLES, false)),
        )
    }

    @Test
    fun `a track the viewer keeps choosing says so as a phrase, not as a separator`() {
        assertEquals(
            "AniLibria, часто выбираете",
            translationLabel(ranked("AniLibria", TranslationKind.VOICE, true)),
        )
        assertEquals(
            "Crunchyroll (субтитры), часто выбираете",
            translationLabel(ranked("Crunchyroll", TranslationKind.SUBTITLES, true)),
        )
    }
}
