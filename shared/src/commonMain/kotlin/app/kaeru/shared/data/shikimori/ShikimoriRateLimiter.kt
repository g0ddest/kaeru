package app.kaeru.shared.data.shikimori

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

/** One process-wide quota across every client in the process. Time is monotonic; waiting is cancellable. */
class ShikimoriRateLimiter(
    private val nowMillis: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
) {
    private val lock = Mutex()
    private val second = ArrayDeque<Long>()
    private val minute = ArrayDeque<Long>()

    suspend fun awaitSlot() = lock.withLock {
        while (true) {
            val now = nowMillis()
            while (second.isNotEmpty() && now - second.first() >= 1000) second.removeFirst()
            while (minute.isNotEmpty() && now - minute.first() >= 60000) minute.removeFirst()
            val wait = maxOf(
                if (second.size >= 5) 1000 - (now - second.first()) else 0,
                if (minute.size >= 90) 60000 - (now - minute.first()) else 0,
            )
            if (wait <= 0) {
                second.addLast(now)
                minute.addLast(now)
                break
            }
            delay(wait)
        }
    }

    companion object { val shared = ShikimoriRateLimiter() }
}
