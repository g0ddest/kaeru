package app.kaeru.data.shikimori

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RateLimitInterceptorTest {
    private var nowNanos = 0L
    private val sleeps = mutableListOf<Long>()
    private val interceptor = RateLimitInterceptor(
        perSecond = 5,
        perMinute = 90,
        clock = { nowNanos },
        sleeper = { milliseconds ->
            sleeps += milliseconds
            nowNanos += milliseconds * 1_000_000
        },
    )

    private fun call() {
        val request = Request.Builder().url("https://shikimori.io/api/x").build()
        val chain = object : Interceptor.Chain {
            override fun request() = request
            override fun proceed(request: Request) =
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").build()

            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        }
        interceptor.intercept(chain)
    }

    @Test
    fun `five requests in one second pass without sleeping`() {
        repeat(5) { call() }

        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `sixth request within a second waits for the window`() {
        repeat(6) { call() }

        assertEquals(1, sleeps.size)
        assertEquals(1000L, sleeps[0])
    }

    @Test
    fun `ninety first request within a minute waits`() {
        repeat(90) {
            call()
            nowNanos += 250_000_000
        }
        val before = sleeps.size

        call()

        assertEquals(before + 1, sleeps.size)
        assertEquals(37_500L, sleeps.last())
    }

    @Test
    fun `request at the exact second boundary does not sleep`() {
        repeat(5) { call() }
        nowNanos = 1_000_000_000L

        call()

        assertEquals(emptyList<Long>(), sleeps)
    }
}
