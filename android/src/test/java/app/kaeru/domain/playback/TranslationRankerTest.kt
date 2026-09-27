package app.kaeru.domain.playback

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The app's tracks through the shared ranking. The ranking itself is tested in `shared`
 * (`app.kaeru.shared.domain.playback.TranslationRankerTest` and the playback vectors); this is
 * only about the tracks coming back as they went in.
 */
class TranslationRankerTest {
    private val preferred = listOf("AniLibria", "AniDUB")

    private val remembered = Translation(10, "Студийная банда", TranslationKind.VOICE, episodesCount = 2, season = 3)
    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12, season = 3)
    private val subtitles = Translation(15, "AniDUB субтитры", TranslationKind.SUBTITLES, episodesCount = 24, season = 3)
    private val longVoice = Translation(13, "Дубляж", TranslationKind.VOICE, episodesCount = 24, season = 3)
    private val available = listOf(subtitles, longVoice, remembered, anilibria)

    @Test
    fun `sort hands back the app's own tracks, seasons and all, in the shared order`() {
        val sorted = TranslationRanker.sort(available, preferred, rememberedId = 10, usage = emptyMap())

        assertEquals(listOf(remembered, anilibria, subtitles, longVoice), sorted)
        sorted.zip(listOf(remembered, anilibria, subtitles, longVoice)).forEach { (a, b) -> assertSame(b, a) }
    }

    @Test
    fun `subtitles and voices reach the ranking as what they are`() {
        val pair = listOf(Translation(1, "Субтитры", TranslationKind.SUBTITLES, 24), Translation(2, "Дубляж", TranslationKind.VOICE, 1))

        assertSame(pair[1], TranslationRanker.pick(pair, emptyList(), rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun `pick is the head of sort and nothing for nothing`() {
        assertSame(remembered, TranslationRanker.pick(available, preferred, rememberedId = 10, usage = emptyMap()))
        assertSame(anilibria, TranslationRanker.pick(available, preferred, rememberedId = null, usage = mapOf(13 to 5)))
        assertNull(TranslationRanker.pick(emptyList(), preferred, rememberedId = 10, usage = emptyMap()))
    }

    @Test
    fun `the stand-in walk is the same order without the chosen track`() {
        assertEquals(
            listOf(anilibria, subtitles, longVoice),
            TranslationRanker.substitutes(available, remembered, preferred, rememberedId = 10, usage = emptyMap()),
        )
    }

    @Test
    fun `the built-in studios are the shared ones`() {
        assertEquals(app.kaeru.shared.domain.playback.TranslationRanker.DEFAULT_STUDIOS, TranslationRanker.DEFAULT_STUDIOS)
    }
}
