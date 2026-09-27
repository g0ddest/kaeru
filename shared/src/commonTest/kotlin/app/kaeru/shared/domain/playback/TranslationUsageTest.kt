package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationUsageTest {
    private fun row(animeId: Int, translationId: Int?) = RememberedTrack(animeId, translationId)

    @Test
    fun eachAnimeCountsOnceForTheTrackItWasLastPlayedWith() {
        val states = listOf(row(1, 11), row(2, 11), row(3, 22))

        assertEquals(mapOf(11 to 2, 22 to 1), TranslationUsage.of(states))
    }

    @Test
    fun anAnimeThatNeverRememberedATrackCountsForNothing() {
        assertEquals(mapOf(11 to 1), TranslationUsage.of(listOf(row(1, null), row(2, 11))))
    }

    @Test
    fun nothingWatchedYetMeansNothingCounted() {
        assertEquals(emptyMap(), TranslationUsage.of(emptyList()))
    }

    @Test
    fun aRepeatedAnimeStillCountsOnceBecauseTheCountIsOfAnimeAndNotOfRows() {
        assertEquals(mapOf(11 to 1), TranslationUsage.of(listOf(row(1, 11), row(1, 11))))
    }

    @Test
    fun theFirstRowOfARepeatedAnimeIsTheOneThatCounts() {
        assertEquals(mapOf(11 to 1), TranslationUsage.of(listOf(row(1, 11), row(1, 22))))
        assertEquals(emptyMap(), TranslationUsage.of(listOf(row(1, null), row(1, 22))))
    }

    @Test
    fun aTrackTwoAnimeCarryIsOftenChosenOneAnimeIsNot() {
        val usage = TranslationUsage.of(listOf(row(1, 11), row(2, 11), row(3, 22)))

        assertEquals(2, TranslationUsage.OFTEN_CHOSEN_FROM)
        assertEquals(listOf(true, false, false), listOf(11, 22, 33).map { TranslationUsage.oftenChosen(usage, it) })
    }
}
