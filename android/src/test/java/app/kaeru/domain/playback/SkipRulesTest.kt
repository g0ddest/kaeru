package app.kaeru.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `an opening in the first minutes of a normal length is kept`() {
        assertEquals(seconds(3, 93), SkipRules.opening(seconds(3, 93), episode))
        assertEquals(seconds(224, 314), SkipRules.opening(seconds(224, 314), episode))
    }

    @Test
    fun `an opening that starts past the first five minutes is not an opening`() {
        assertNull(SkipRules.opening(seconds(310, 400), episode))
    }

    @Test
    fun `an interval too short or too long to be an opening is dropped`() {
        assertNull(SkipRules.opening(seconds(10, 50), episode))
        assertNull(SkipRules.opening(seconds(10, 200), episode))
    }

    @Test
    fun `an ending in the last minutes of the episode is kept`() {
        assertEquals(seconds(1460, 1560), SkipRules.ending(seconds(1460, 1560), episode))
    }

    @Test
    fun `an ending marked at the start of the episode is thrown away`() {
        // What AniSkip answered for Dandadan, and for «История о перекуре за супермаркетом».
        assertNull(SkipRules.ending(seconds(5, 95), episode))
        assertNull(SkipRules.ending(seconds(117, 207), episode))
    }

    @Test
    fun `an interval that runs past the end of this file belongs to another length`() {
        assertNull(SkipRules.opening(seconds(3, 93), 60_000))
        assertNull(SkipRules.ending(seconds(1460, 1560), 1_400_000))
    }

    @Test
    fun `nothing is accepted for an episode of no known length`() {
        assertNull(SkipRules.opening(seconds(3, 93), 0))
        assertEquals(SkipMarks.NONE, SkipRules.accept(SkipMarks(seconds(3, 93), seconds(1460, 1560)), 0))
    }

    @Test
    fun `accepting keeps the good half of a pair and drops the other`() {
        val marks = SkipMarks(opening = seconds(3, 93), ending = seconds(5, 95))

        assertEquals(SkipMarks(opening = seconds(3, 93)), SkipRules.accept(marks, episode))
    }

    // --- the ten seconds a button hangs for ------------------------------------------------------

    private val marks = SkipMarks(opening = seconds(3, 93), ending = seconds(1460, 1560))

    @Test
    fun `the opening button is offered for ten seconds from the moment the opening starts`() {
        assertNull(SkipRules.offer(marks, 2_999, episode))
        assertEquals(SkipOffer(SkipKind.OPENING, seconds(3, 93)), SkipRules.offer(marks, 3_000, episode))
        assertEquals(SkipOffer(SkipKind.OPENING, seconds(3, 93)), SkipRules.offer(marks, 12_999, episode))
        assertNull(SkipRules.offer(marks, 13_000, episode))
    }

    @Test
    fun `the ending button is offered on the same ten seconds`() {
        assertNull(SkipRules.offer(marks, 1_459_000, episode))
        assertEquals(SkipOffer(SkipKind.ENDING, seconds(1460, 1560)), SkipRules.offer(marks, 1_460_000, episode))
        assertNull(SkipRules.offer(marks, 1_470_000, episode))
    }

    @Test
    fun `a broken ending offers nothing however long it is played`() {
        val broken = SkipMarks(ending = seconds(5, 95))

        assertNull(SkipRules.offer(broken, 5_000, episode))
        assertNull(SkipRules.offer(broken, 10_000, episode))
        assertFalse(SkipRules.endingSkipDue(broken, 20_000, episode))
    }

    @Test
    fun `the ending skips itself once its ten seconds are behind the viewer`() {
        assertFalse(SkipRules.endingSkipDue(marks, 1_469_999, episode))
        assertTrue(SkipRules.endingSkipDue(marks, 1_470_000, episode))
    }

    @Test
    fun `an ending already played out is not skipped again`() {
        assertFalse(SkipRules.endingSkipDue(marks, 1_560_000, episode))
    }
}
