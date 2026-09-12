package app.kaeru.test

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [Clock] whose instant the test moves by hand, for asserting cache expiry without sleeping. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
