package app.kaeru.data.notify

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * The five values the check is scheduled with, asserted rather than trusted.
 *
 * Every one of them is a constant that could be wrong without a single test going red, and each
 * is a different kind of wrong: a period an hour out would drain a battery, a missing network
 * constraint would burn retries in a tunnel, and `REPLACE` instead of `KEEP` would restart the
 * period on every app launch and mean the check never ran at all on a phone opened twice a day.
 *
 * The request is built here the way `enable()` builds it, which is the whole of what `enable()`
 * decides — what it then does with it is one call into WorkManager, which needs a device.
 */
@RunWith(RobolectricTestRunner::class)
class NewEpisodesScheduleTest {
    private val spec = WorkManagerNewEpisodesSchedule.request().workSpec

    @Test
    fun `the check runs four times a day`() {
        assertEquals(Duration.ofHours(6).toMillis(), spec.intervalDuration)
    }

    @Test
    fun `it waits for a network rather than failing into a retry`() {
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `it stands aside for a battery that is nearly out`() {
        assertTrue(spec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `the work is this app's own new-episode check`() {
        assertEquals(NewEpisodesWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun `a second app start keeps the check already scheduled instead of restarting its period`() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, WorkManagerNewEpisodesSchedule.POLICY)
    }

    @Test
    fun `the unique name is the one the device is inspected by`() {
        assertEquals("new-episodes", NewEpisodesWorker.NAME)
    }
}
