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
}
