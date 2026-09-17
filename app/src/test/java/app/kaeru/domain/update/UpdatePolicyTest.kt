package app.kaeru.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The throttle on the quiet check, read against a clock the test moves by hand. */
class UpdatePolicyTest {
    private val policy = UpdatePolicy()
    private val start: Instant = Instant.parse("2026-09-16T08:00:00Z")

    @Test
    fun `a device that has never checked is due`() {
        assertTrue(policy.due(lastCheckedAt = null, now = start))
    }

    @Test
    fun `a check that just ran is not due again`() {
        assertFalse(policy.due(lastCheckedAt = start, now = start))
    }

    @Test
    fun `an app restarted an hour later does not check again`() {
        assertFalse(policy.due(start, start.plus(Duration.ofHours(1))))
    }

    @Test
    fun `one minute short of a day is still not due`() {
        assertFalse(policy.due(start, start.plus(Duration.ofHours(24)).minusSeconds(60)))
    }

    @Test
    fun `a day later it is due`() {
        assertTrue(policy.due(start, start.plus(Duration.ofHours(24))))
    }

    @Test
    fun `a week later it is due`() {
        assertTrue(policy.due(start, start.plus(Duration.ofDays(7))))
    }

    /**
     * A device whose clock was wrong and has been corrected has a check recorded in its own
     * future. Waiting that out would mean a device that never checks again, so the answer is yes.
     */
    @Test
    fun `a check recorded in the future is due now`() {
        assertTrue(policy.due(start.plus(Duration.ofDays(30)), start))
    }

    @Test
    fun `the interval is the one it was built with`() {
        val hourly = UpdatePolicy(Duration.ofHours(1))

        assertFalse(hourly.due(start, start.plusSeconds(59 * 60)))
        assertTrue(hourly.due(start, start.plus(Duration.ofHours(1))))
    }
}
