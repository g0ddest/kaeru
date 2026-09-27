package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationRankerTest {
    private val preferred = listOf("AniLibria", "AniDUB", "Crunchyroll")

    private fun voice(id: Int, title: String, episodes: Int? = null) =
        TranslationCandidate(id, title, TrackKind.VOICE, episodes)

    private fun subs(id: Int, title: String, episodes: Int? = null) =
        TranslationCandidate(id, title, TrackKind.SUBTITLES, episodes)

    private fun pick(
        available: List<TranslationCandidate>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ) = TranslationRanker.pick(available, preferred, rememberedId, usage)

    @Test
    fun theRememberedTranslationWinsOverAPreferredStudioAndOverEpisodeCounts() {
        val available = listOf(
            voice(1, "AniLibria.TV", episodes = 12),
            voice(2, "Студийная банда", episodes = 24),
            subs(3, "Crunchyroll", episodes = 24),
        )

        assertEquals(available[1], pick(available, preferred, rememberedId = 2, usage = emptyMap()))
    }

    @Test
    fun aRememberedIdTheSourceNoLongerOffersFallsThroughToTheNormalRules() {
        val available = listOf(voice(1, "Студийная банда", episodes = 24), voice(2, "AniDUB", episodes = 6))

        assertEquals(available[1], pick(available, preferred, rememberedId = 99, usage = emptyMap()))
    }

    @Test
    fun preferredStudiosAreTriedInOrderNotByHowManyEpisodesTheyCarry() {
        val available = listOf(
            voice(1, "Crunchyroll", episodes = 24),
            voice(2, "AniDUB", episodes = 12),
            voice(3, "AniLibria", episodes = 3),
        )

        assertEquals(available[2], pick(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun aPreferredStudioMatchesAnywhereInTheTitleRegardlessOfCase() {
        val available = listOf(
            voice(1, "Студийная банда", episodes = 24),
            voice(2, "Дубляж [anilibria.tv]", episodes = 12),
        )

        assertEquals(available[1], pick(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun withoutAPreferredMatchTheVoiceWithTheMostEpisodesWinsOverLongerSubtitles() {
        val available = listOf(
            voice(1, "Студийная банда", episodes = 6),
            subs(2, "Субтитры", episodes = 24),
            voice(3, "Дубляж", episodes = 12),
        )

        assertEquals(available[2], pick(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun anUnknownEpisodeCountNeverOutranksAKnownOneAndTiesKeepSourceOrder() {
        val available = listOf(
            voice(1, "Неизвестно", episodes = null),
            voice(2, "Первая", episodes = 12),
            voice(3, "Вторая", episodes = 12),
        )

        assertEquals(available[1], pick(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun subtitlesOnlySourcesStillYieldTheirFirstTrack() {
        val available = listOf(subs(1, "Субтитры A"), subs(2, "Субтитры B", episodes = 24))

        assertEquals(available[0], pick(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun nothingOnOfferMeansNothingToPick() {
        assertNull(pick(emptyList(), preferred, rememberedId = 1, usage = emptyMap()))
        assertEquals(emptyList(), TranslationRanker.sort(emptyList(), preferred, rememberedId = 1, usage = emptyMap()))
        assertEquals(emptyList(), TranslationRanker.order(emptyList(), preferred, rememberedId = 1, usage = emptyMap()))
    }

    @Test
    fun withNoStudioMatchingAnywhereTheVoiceAndEpisodeRulesAreInCharge() {
        val available = listOf(subs(1, "Неизвестные субтитры", episodes = 24), voice(2, "Дубляж", episodes = 12))

        assertEquals(available[1], pick(available, emptyList(), rememberedId = null, usage = emptyMap()))
    }

    // --- the viewer's own history --------------------------------------------------------------

    @Test
    fun theStudioListTheViewerSetByHandBeatsTheOneTheyUseMost() {
        val available = listOf(voice(1, "Студийная банда", episodes = 24), voice(2, "AniDUB", episodes = 6))

        assertEquals(available[1], pick(available, preferred, rememberedId = null, usage = mapOf(1 to 9)))
    }

    @Test
    fun betweenStudiosNobodyListedTheOneChosenForMoreAnimeWins() {
        val available = listOf(
            voice(1, "Студийная банда", episodes = 24),
            voice(2, "Дубляж", episodes = 6),
            voice(3, "Озвучка", episodes = 12),
        )

        assertEquals(available[1], pick(available, preferred, rememberedId = null, usage = mapOf(2 to 3, 3 to 1)))
    }

    @Test
    fun aTrackChosenOftenBeatsAStudioOnlyTheBuiltInListKnows() {
        val available = listOf(voice(1, "AniLibria.TV", episodes = 12), voice(2, "Студийная банда", episodes = 12))

        assertEquals(available[1], pick(available, emptyList(), rememberedId = null, usage = mapOf(2 to 4)))
    }

    @Test
    fun withNothingChosenYetTheBuiltInStudiosDecide() {
        val available = listOf(voice(1, "Студийная банда", episodes = 24), voice(2, "AniLibria.TV", episodes = 6))

        assertEquals(available[1], pick(available, emptyList(), rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun theBuiltInStudiosOutrankADubOverSubtitlesTheWayAListedStudioDoes() {
        val available = listOf(voice(1, "Дубляж", episodes = 24), subs(2, "AniLibria субтитры", episodes = 12))

        assertEquals(available[1], pick(available, emptyList(), rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun theRememberedTrackStillWinsOverEverythingTheHistorySays() {
        val available = listOf(voice(1, "AniLibria.TV", episodes = 12), voice(2, "Студийная банда", episodes = 24))

        assertEquals(available[1], pick(available, preferred, rememberedId = 2, usage = mapOf(1 to 7)))
    }

    @Test
    fun theBuiltInStudiosAreTheNineTheAppShippedWithInOrder() {
        assertEquals(
            listOf(
                "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza",
                "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
            ),
            TranslationRanker.DEFAULT_STUDIOS,
        )
    }

    @Test
    fun sortOrdersByHowOftenTracksAreChosenOnceTheListedStudiosRunOut() {
        val listed = voice(1, "AniDUB", episodes = 3)
        val used = voice(2, "Студийная банда", episodes = 3)
        val usedLess = voice(3, "Дубляж", episodes = 3)
        val builtIn = voice(4, "Dream Cast", episodes = 3)
        val available = listOf(usedLess, builtIn, used, listed)

        val sorted = TranslationRanker.sort(available, preferred, rememberedId = null, usage = mapOf(2 to 5, 3 to 2))

        assertEquals(listOf(listed, used, usedLess, builtIn), sorted)
    }

    @Test
    fun sortPutsTheRememberedTrackFirstThenPreferredThenVoicesByEpisodesThenSubtitles() {
        val remembered = voice(10, "Студийная банда", episodes = 2)
        val anilibria = voice(11, "AniLibria.TV", episodes = 12)
        val anidub = voice(12, "AniDUB", episodes = 6)
        val longVoice = voice(13, "Дубляж", episodes = 24)
        val shortVoice = voice(14, "Озвучка", episodes = 3)
        val subtitles = subs(15, "Субтитры", episodes = 24)
        val available = listOf(subtitles, shortVoice, anidub, longVoice, remembered, anilibria)

        val sorted = TranslationRanker.sort(available, preferred, rememberedId = 10, usage = emptyMap())

        assertEquals(listOf(remembered, anilibria, anidub, longVoice, shortVoice, subtitles), sorted)
        assertEquals(listOf(4, 5, 2, 3, 1, 0), TranslationRanker.order(available, preferred, 10, emptyMap()))
    }

    @Test
    fun sortIsStableForTracksTheRulesCannotTellApart() {
        val available = listOf(
            voice(1, "Первая", episodes = 12),
            voice(2, "Вторая", episodes = 12),
            voice(3, "Третья", episodes = 12),
        )

        assertEquals(available, TranslationRanker.sort(available, preferred, rememberedId = null, usage = emptyMap()))
    }

    @Test
    fun pickAlwaysAgreesWithTheHeadOfSort() {
        val cases = listOf(
            listOf(voice(1, "AniDUB", 12), voice(2, "AniLibria", 3), subs(3, "Crunchyroll", 24)),
            listOf(subs(1, "Субтитры", 24), voice(2, "Дубляж", null)),
            listOf(voice(1, "Одна", null), voice(2, "Другая", null)),
            emptyList(),
        )
        val histories = listOf(emptyMap(), mapOf(1 to 4), mapOf(2 to 1, 3 to 6))

        for (available in cases) {
            for (remembered in listOf(null, 1, 3, 99)) {
                for (usage in histories) {
                    assertEquals(
                        TranslationRanker.sort(available, preferred, remembered, usage).firstOrNull(),
                        pick(available, preferred, remembered, usage),
                    )
                }
            }
        }
    }

    // --- the walk for a stand-in -----------------------------------------------------------------

    @Test
    fun theSubstitutionWalkIsTheRankingWithTheChosenTrackTakenOut() {
        val remembered = voice(10, "Студийная банда", episodes = 2)
        val anilibria = voice(11, "AniLibria.TV", episodes = 12)
        val anidub = voice(12, "AniDUB", episodes = 6)
        val subtitles = subs(15, "Субтитры", episodes = 24)
        val available = listOf(subtitles, anidub, remembered, anilibria)

        assertEquals(
            listOf(3, 1, 0),
            TranslationRanker.substitutionOrder(available, chosenId = 10, preferred, rememberedId = 10, usage = emptyMap()),
        )
        assertEquals(
            listOf(2, 3, 0),
            TranslationRanker.substitutionOrder(available, chosenId = 12, preferred, rememberedId = 10, usage = emptyMap()),
        )
    }

    // --- whether a track carries an episode ------------------------------------------------------

    @Test
    fun aTracksOwnListOfEpisodesIsTheAnswer() {
        assertEquals(true, TranslationRanker.carriesEpisode(3, listedEpisodes = setOf(1, 2, 3), season = 2, episodesCount = null))
        assertEquals(false, TranslationRanker.carriesEpisode(4, listedEpisodes = setOf(1, 2, 3), season = 1, episodesCount = 24))
        assertEquals(false, TranslationRanker.lacksEpisode(3, setOf(1, 2, 3), 1, null))
        assertTrue(TranslationRanker.lacksEpisode(4, setOf(1, 2, 3), 1, null))
    }

    @Test
    fun withoutAListTheCountAnswersOnlyForTheFirstSeason() {
        assertEquals(true, TranslationRanker.carriesEpisode(12, listedEpisodes = null, season = 1, episodesCount = 12))
        assertEquals(false, TranslationRanker.carriesEpisode(13, listedEpisodes = null, season = 1, episodesCount = 12))
        assertNull(TranslationRanker.carriesEpisode(13, listedEpisodes = null, season = 2, episodesCount = 12))
        assertNull(TranslationRanker.carriesEpisode(13, listedEpisodes = null, season = 1, episodesCount = null))
        assertFalse(TranslationRanker.lacksEpisode(13, null, 2, 12))
    }

    @Test
    fun theSubstitutionIsSaidInOneSentence() {
        assertEquals(
            "В озвучке AniDUB серии 7 нет — включена AniLibria.TV",
            TranslationRanker.substitutionNotice(askedFor = "AniDUB", episode = 7, playing = "AniLibria.TV"),
        )
    }
}
