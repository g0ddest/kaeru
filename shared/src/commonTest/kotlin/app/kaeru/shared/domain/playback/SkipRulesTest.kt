package app.kaeru.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Common sense over community data, and the ten seconds a button is worth.
 *
 * Every number here came off a real AniSkip answer during the spike: Frieren's opening at 3–93
 * seconds of a 1560-second file, and the two answers that were plainly wrong — Dandadan's
 * «ending» at 5–95 seconds and «Перекур»'s at 117–207. Nothing on screen may depend on a viewer
 * noticing that an ending was marked in the first two minutes, so the rule is here rather than
 * anywhere a button is drawn.
 */
class SkipRulesTest {

    private val episode = 1_560_000L

    private fun seconds(from: Long, to: Long) = SkipInterval(from * 1000, to * 1000)

    @Test
    fun anOpeningInTheFirstMinutesOfANormalLengthIsKept() {
        assertEquals(seconds(3, 93), SkipRules.opening(seconds(3, 93), episode))
        assertEquals(seconds(224, 314), SkipRules.opening(seconds(224, 314), episode))
    }

    @Test
    fun anOpeningThatStartsPastTheFirstFiveMinutesIsNotAnOpening() {
        assertNull(SkipRules.opening(seconds(310, 400), episode))
    }

    @Test
    fun anIntervalTooShortOrTooLongToBeAnOpeningIsDropped() {
        assertNull(SkipRules.opening(seconds(10, 50), episode))
        assertNull(SkipRules.opening(seconds(10, 200), episode))
    }

    @Test
    fun anEndingInTheLastMinutesOfTheEpisodeIsKept() {
        assertEquals(seconds(1460, 1560), SkipRules.ending(seconds(1460, 1560), episode))
    }

    @Test
    fun anEndingMarkedAtTheStartOfTheEpisodeIsThrownAway() {
        // What AniSkip answered for Dandadan, and for «История о перекуре за супермаркетом».
        assertNull(SkipRules.ending(seconds(5, 95), episode))
        assertNull(SkipRules.ending(seconds(117, 207), episode))
    }

    @Test
    fun anIntervalThatRunsPastTheEndOfThisFileBelongsToAnotherLength() {
        assertNull(SkipRules.opening(seconds(3, 93), 60_000))
        assertNull(SkipRules.ending(seconds(1460, 1560), 1_400_000))
    }

    @Test
    fun nothingIsAcceptedForAnEpisodeOfNoKnownLength() {
        assertNull(SkipRules.opening(seconds(3, 93), 0))
        assertEquals(SkipMarks.NONE, SkipRules.accept(SkipMarks(seconds(3, 93), seconds(1460, 1560)), 0))
    }

    @Test
    fun acceptingKeepsTheGoodHalfOfAPairAndDropsTheOther() {
        val marks = SkipMarks(opening = seconds(3, 93), ending = seconds(5, 95))

        assertEquals(SkipMarks(opening = seconds(3, 93)), SkipRules.accept(marks, episode))
    }

    // --- the ten seconds a button hangs for ------------------------------------------------------

    private val marks = SkipMarks(opening = seconds(3, 93), ending = seconds(1460, 1560))

    @Test
    fun theOpeningButtonIsOfferedForTenSecondsFromTheMomentTheOpeningStarts() {
        assertNull(SkipRules.offer(marks, 2_999, episode))
        assertEquals(SkipOffer(SkipKind.OPENING, seconds(3, 93)), SkipRules.offer(marks, 3_000, episode))
        assertEquals(SkipOffer(SkipKind.OPENING, seconds(3, 93)), SkipRules.offer(marks, 12_999, episode))
        assertNull(SkipRules.offer(marks, 13_000, episode))
    }

    @Test
    fun theEndingButtonIsOfferedOnTheSameTenSeconds() {
        assertNull(SkipRules.offer(marks, 1_459_000, episode))
        assertEquals(SkipOffer(SkipKind.ENDING, seconds(1460, 1560)), SkipRules.offer(marks, 1_460_000, episode))
        assertNull(SkipRules.offer(marks, 1_470_000, episode))
    }

    @Test
    fun aBrokenEndingOffersNothingHoweverLongItIsPlayed() {
        val broken = SkipMarks(ending = seconds(5, 95))

        assertNull(SkipRules.offer(broken, 5_000, episode))
        assertNull(SkipRules.offer(broken, 10_000, episode))
        assertFalse(SkipRules.endingSkipDue(broken, 20_000, episode))
    }

    @Test
    fun theEndingSkipsItselfOnceItsTenSecondsAreBehindTheViewer() {
        assertFalse(SkipRules.endingSkipDue(marks, 1_469_999, episode))
        assertTrue(SkipRules.endingSkipDue(marks, 1_470_000, episode))
    }

    @Test
    fun anEndingAlreadyPlayedOutIsNotSkippedAgain() {
        assertFalse(SkipRules.endingSkipDue(marks, 1_560_000, episode))
    }

    @Test
    fun insideTheEndingIsFromItsFirstMillisecondToJustBeforeItsLast() {
        assertFalse(SkipRules.insideEnding(marks, 1_459_999, episode))
        assertTrue(SkipRules.insideEnding(marks, 1_460_000, episode))
        assertTrue(SkipRules.insideEnding(marks, 1_559_999, episode))
        assertFalse(SkipRules.insideEnding(marks, 1_560_000, episode))
        assertFalse(SkipRules.insideEnding(SkipMarks(ending = seconds(5, 95)), 50_000, episode))
    }

    @Test
    fun theWindowsAreTheOnesAndroidShipped() {
        assertEquals(10_000L, SkipRules.BUTTON_WINDOW_MS)
        assertEquals(300_000L, SkipRules.OPENING_STARTS_WITHIN_MS)
        assertEquals(180_000L, SkipRules.ENDING_ENDS_WITHIN_MS)
        assertEquals(60_000L, SkipRules.MIN_LENGTH_MS)
        assertEquals(150_000L, SkipRules.MAX_LENGTH_MS)
        assertEquals(1_000L, SkipRules.SEEK_SETTLE_MS)
        assertTrue(SkipMarks.NONE.isEmpty)
    }
}
