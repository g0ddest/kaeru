package app.kaeru.data.library

import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.sync.OutboxSyncer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The one thing that notices the network is back and empties the queue into Shikimori. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncStarterTest {
    private val online = MutableStateFlow(false)
    private val connectivity = object : Connectivity {
        override val online: Flow<Boolean> = this@OfflineSyncStarterTest.online
    }
    private var replays = 0
    private var failNext = false

    private val syncer = OutboxSyncer {
        replays++
        if (failNext) Result.failure(IOException("still gone")) else Result.success(1)
    }

    @Test
    fun `nothing is sent while the device is offline`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        OfflineSyncStarter(connectivity, syncer).start(scope)
        scope.runCurrent()

        assertEquals(0, replays)
    }

    @Test
    fun `the queue is emptied as soon as the network comes back`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        OfflineSyncStarter(connectivity, syncer).start(scope)
        scope.runCurrent()

        online.value = true
        scope.runCurrent()

        assertEquals(1, replays)
    }

    @Test
    fun `every return of the network is another chance to send`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        OfflineSyncStarter(connectivity, syncer).start(scope)
        online.value = true
        scope.runCurrent()
        online.value = false
        scope.runCurrent()
        online.value = true
        scope.runCurrent()

        assertEquals(2, replays)
    }

    @Test
    fun `a replay that fails does not stop the next one`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        OfflineSyncStarter(connectivity, syncer).start(scope)
        failNext = true
        online.value = true
        scope.runCurrent()
        online.value = false
        failNext = false
        scope.runCurrent()
        online.value = true
        scope.runCurrent()

        assertEquals(2, replays)
    }

    @Test
    fun `starting twice still watches once`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val starter = OfflineSyncStarter(connectivity, syncer)
        starter.start(scope)
        starter.start(scope)
        online.value = true
        scope.runCurrent()

        assertEquals(1, replays)
    }
}
