package app.kaeru.domain.playback

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationRankerTest {
    private val preferred = listOf("AniLibria", "AniDUB", "Crunchyroll")

    private fun voice(id: Int, title: String, episodes: Int? = null) =
        Translation(id, title, TranslationKind.VOICE, episodes)

    private fun subs(id: Int, title: String, episodes: Int? = null) =
        Translation(id, title, TranslationKind.SUBTITLES, episodes)

    @Test
    fun `the remembered translation wins over a preferred studio and over episode counts`() {
        val available = listOf(
            voice(1, "AniLibria.TV", episodes = 12),
            voice(2, "Студийная банда", episodes = 24),
            subs(3, "Crunchyroll", episodes = 24),
        )

        assertEquals(available[1], TranslationRanker.pick(available, preferred, rememberedId = 2))
    }

    @Test
    fun `a remembered id the source no longer offers falls through to the normal rules`() {
        val available = listOf(voice(1, "Студийная банда", episodes = 24), voice(2, "AniDUB", episodes = 6))

        assertEquals(available[1], TranslationRanker.pick(available, preferred, rememberedId = 99))
    }

    @Test
    fun `preferred studios are tried in order, not by how many episodes they carry`() {
        val available = listOf(
            voice(1, "Crunchyroll", episodes = 24),
            voice(2, "AniDUB", episodes = 12),
            voice(3, "AniLibria", episodes = 3),
        )

        assertEquals(available[2], TranslationRanker.pick(available, preferred, rememberedId = null))
    }

    @Test
    fun `a preferred studio matches anywhere in the title regardless of case`() {
        val available = listOf(
            voice(1, "Студийная банда", episodes = 24),
            voice(2, "Дубляж [anilibria.tv]", episodes = 12),
        )

        assertEquals(available[1], TranslationRanker.pick(available, preferred, rememberedId = null))
    }

    @Test
    fun `without a preferred match the voice with the most episodes wins over longer subtitles`() {
        val available = listOf(
            voice(1, "Студийная банда", episodes = 6),
            subs(2, "Субтитры", episodes = 24),
            voice(3, "Дубляж", episodes = 12),
        )

        assertEquals(available[2], TranslationRanker.pick(available, preferred, rememberedId = null))
    }

    @Test
    fun `an unknown episode count never outranks a known one and ties keep source order`() {
        val available = listOf(
            voice(1, "Неизвестно", episodes = null),
            voice(2, "Первая", episodes = 12),
            voice(3, "Вторая", episodes = 12),
        )

        assertEquals(available[1], TranslationRanker.pick(available, preferred, rememberedId = null))
    }

    @Test
    fun `subtitles only sources still yield their first track`() {
        val available = listOf(subs(1, "Субтитры A"), subs(2, "Субтитры B", episodes = 24))

        assertEquals(available[0], TranslationRanker.pick(available, preferred, rememberedId = null))
    }

    @Test
    fun `nothing on offer means nothing to pick`() {
        assertNull(TranslationRanker.pick(emptyList(), preferred, rememberedId = 1))
        assertEquals(emptyList<Translation>(), TranslationRanker.sort(emptyList(), preferred, rememberedId = 1))
    }

    @Test
    fun `an empty preference list leaves the voice and episode rules in charge`() {
        val available = listOf(subs(1, "AniLibria субтитры", episodes = 24), voice(2, "Дубляж", episodes = 12))

        assertEquals(available[1], TranslationRanker.pick(available, emptyList(), rememberedId = null))
    }

    @Test
    fun `sort puts the remembered track first, then preferred in order, then voices by episodes, then subtitles`() {
        val remembered = voice(10, "Студийная банда", episodes = 2)
        val anilibria = voice(11, "AniLibria.TV", episodes = 12)
        val anidub = voice(12, "AniDUB", episodes = 6)
        val longVoice = voice(13, "Дубляж", episodes = 24)
        val shortVoice = voice(14, "Озвучка", episodes = 3)
        val subtitles = subs(15, "Субтитры", episodes = 24)
        val available = listOf(subtitles, shortVoice, anidub, longVoice, remembered, anilibria)

        val sorted = TranslationRanker.sort(available, preferred, rememberedId = 10)

        assertEquals(listOf(remembered, anilibria, anidub, longVoice, shortVoice, subtitles), sorted)
    }

    @Test
    fun `sort is stable for tracks the rules cannot tell apart`() {
        val available = listOf(
            voice(1, "Первая", episodes = 12),
            voice(2, "Вторая", episodes = 12),
            voice(3, "Третья", episodes = 12),
        )

        assertEquals(available, TranslationRanker.sort(available, preferred, rememberedId = null))
    }

    @Test
    fun `pick always agrees with the head of sort`() {
        val cases = listOf(
            listOf(voice(1, "AniDUB", 12), voice(2, "AniLibria", 3), subs(3, "Crunchyroll", 24)),
            listOf(subs(1, "Субтитры", 24), voice(2, "Дубляж", null)),
            listOf(voice(1, "Одна", null), voice(2, "Другая", null)),
            emptyList(),
        )

        for (available in cases) {
            for (remembered in listOf(null, 1, 3, 99)) {
                assertEquals(
                    TranslationRanker.sort(available, preferred, remembered).firstOrNull(),
                    TranslationRanker.pick(available, preferred, remembered),
                )
            }
        }
    }
}
