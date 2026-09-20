package app.kaeru.shared.data.shikimori

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShikimoriRateLimiterTest {
    @Test fun concurrentRequestsObeyBothSlidingWindows() = runTest {
        val limiter = ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime })
        val admitted = (1..91).map { async { limiter.awaitSlot(); testScheduler.currentTime } }.awaitAll().sorted()
        assertEquals(List(5) { 0L }, admitted.take(5))
        assertEquals(1000L, admitted[5])
        assertEquals(60000L, admitted[90])
        for (time in admitted) {
            assertTrue(admitted.count { it > time - 1000 && it <= time } <= 5)
            assertTrue(admitted.count { it > time - 60000 && it <= time } <= 90)
        }
    }

    @Test fun theSixthRequestInASecondWaitsForTheWindowAndTheBoundaryItselfDoesNot() = runTest {
        val limiter = ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime })
        repeat(5) { limiter.awaitSlot() }
        assertEquals(0L, testScheduler.currentTime)
        limiter.awaitSlot()
        assertEquals(1000L, testScheduler.currentTime)
        // Exactly a second after the first of the last five: the window has moved on, no wait.
        val boundary = ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime })
        repeat(5) { boundary.awaitSlot() }
        testScheduler.advanceTimeBy(1000)
        val before = testScheduler.currentTime
        boundary.awaitSlot()
        assertEquals(before, testScheduler.currentTime)
    }

    @Test fun theNinetyFirstRequestInAMinuteWaitsForTheOldestToAge() = runTest {
        val limiter = ShikimoriRateLimiter(nowMillis = { testScheduler.currentTime })
        repeat(90) { limiter.awaitSlot(); testScheduler.advanceTimeBy(250) }
        val before = testScheduler.currentTime
        limiter.awaitSlot()
        assertEquals(37_500L, testScheduler.currentTime - before)
    }
}
