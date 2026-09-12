package app.kaeru.data.shikimori

import okhttp3.Interceptor
import okhttp3.Response
import java.util.ArrayDeque

/**
 * Sliding-window limiter for Shikimori's per-second and per-minute request quotas.
 * The calling OkHttp thread sleeps until both windows have capacity.
 */
class RateLimitInterceptor(
    private val perSecond: Int = 5,
    private val perMinute: Int = 90,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : Interceptor {
    private val lock = Any()
    private val second = ArrayDeque<Long>()
    private val minute = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        awaitSlot()
        return chain.proceed(chain.request())
    }

    private fun awaitSlot() {
        synchronized(lock) {
            while (true) {
                val now = clock()
                trim(second, now, SECOND_NANOS)
                trim(minute, now, MINUTE_NANOS)

                val secondWait = waitNanos(second, perSecond, now, SECOND_NANOS)
                val minuteWait = waitNanos(minute, perMinute, now, MINUTE_NANOS)
                val waitNanos = maxOf(secondWait, minuteWait)
                if (waitNanos <= 0L) {
                    second.addLast(now)
                    minute.addLast(now)
                    return
                }

                sleeper((waitNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            }
        }
    }

    private fun waitNanos(
        timestamps: ArrayDeque<Long>,
        limit: Int,
        now: Long,
        windowNanos: Long,
    ): Long = if (timestamps.size >= limit) {
        windowNanos - (now - timestamps.first)
    } else {
        0L
    }

    private fun trim(timestamps: ArrayDeque<Long>, now: Long, windowNanos: Long) {
        while (timestamps.isNotEmpty() && now - timestamps.first >= windowNanos) {
            timestamps.removeFirst()
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SECOND_NANOS = 1_000_000_000L
        const val MINUTE_NANOS = 60_000_000_000L
    }
}
