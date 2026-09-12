package app.kaeru.domain.playback

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.model.WatchState
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val watchStates = FakeWatchStateRepository()
    private val progress = WatchProgress(watchStates, clock)

    @Test
    fun `a sample with nothing in flight is written straight through`() = runTest {
        progress.report(animeId = 100, episode = 4, positionMs = 65_000, durationMs = 1_400_000, translationId = 7)

        assertEquals(
            WatchState(100, 4, 65_000, 1_400_000, translationId = 7, kodikSeason = null, updatedAt = now),
            watchStates.saved.single(),
        )
    }

    @Test
    fun `while a save is parked the newest sample wins and the stale ones are dropped`() = runTest {
        watchStates.block()

        launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()
        assertEquals(listOf(5_000L), watchStates.started.map { it.positionMs })

        launch { progress.report(100, 4, 10_000, 1_400_000, 7) }
        launch { progress.report(100, 4, 15_000, 1_400_000, 7) }
        runCurrent()
        assertEquals(1, watchStates.started.size)

        watchStates.release()
        advanceUntilIdle()

        assertEquals(listOf(5_000L, 15_000L), watchStates.saved.map { it.positionMs })
        assertEquals(1, watchStates.peakConcurrentSaves)
    }

    @Test
    fun `reporting while a save is parked does not make the reporter wait for it`() = runTest {
        watchStates.block()
        launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()
        var returned = false

        launch {
            progress.report(100, 4, 10_000, 1_400_000, 7)
            returned = true
        }
        runCurrent()

        assertTrue(returned)
        watchStates.release()
        advanceUntilIdle()
    }

    @Test
    fun `two anime in flight at once each keep their own newest sample`() = runTest {
        watchStates.block()
        launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()

        launch { progress.report(200, 1, 1_000, 600_000, 9) }
        launch { progress.report(200, 1, 2_000, 600_000, 9) }
        runCurrent()
        watchStates.release()
        advanceUntilIdle()

        assertEquals(listOf(100 to 5_000L, 200 to 2_000L), watchStates.saved.map { it.animeId to it.positionMs })
    }

    @Test
    fun `a sample is stamped when it was taken, not when the queue got around to it`() = runTest {
        watchStates.block()
        launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()
        launch { progress.report(100, 4, 10_000, 1_400_000, 7) }
        runCurrent()
        val taken = clock.now

        clock.advance(Duration.ofSeconds(30))
        watchStates.release()
        advanceUntilIdle()

        assertEquals(listOf(taken, taken), watchStates.saved.map { it.updatedAt })
    }

    @Test
    fun `a caller that does not know the track or season keeps what is already remembered`() = runTest {
        watchStates.seed(WatchState(100, 3, 900_000, 1_400_000, translationId = 7, kodikSeason = 2, updatedAt = now))

        progress.report(animeId = 100, episode = 4, positionMs = 1_000, durationMs = 1_300_000, translationId = null)

        val saved = watchStates.saved.single()
        assertEquals(7, saved.translationId)
        assertEquals(2, saved.kodikSeason)
        assertEquals(4, saved.episode)
        assertEquals(1_000L, saved.positionMs)
    }

    @Test
    fun `a caller that does know the track overrides what was remembered`() = runTest {
        watchStates.seed(WatchState(100, 3, 900_000, 1_400_000, translationId = 7, kodikSeason = 2, updatedAt = now))

        progress.report(100, 4, 1_000, 1_300_000, translationId = 11, kodikSeason = 3)

        val saved = watchStates.saved.single()
        assertEquals(11, saved.translationId)
        assertEquals(3, saved.kodikSeason)
    }

    @Test
    fun `a failed save is swallowed and the next sample still gets through`() = runTest {
        watchStates.failSaveWith = AccountSessionChanged("signed out")

        progress.report(100, 4, 5_000, 1_400_000, 7)
        watchStates.failSaveWith = null
        progress.report(100, 4, 10_000, 1_400_000, 7)

        assertEquals(listOf(10_000L), watchStates.saved.map { it.positionMs })
    }

    @Test
    fun `a sample queued behind a cancelled owner is still written`() = runTest {
        watchStates.block()
        val owner = launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()
        launch { progress.report(100, 4, 12_000, 1_400_000, 7, kodikSeason = 2) }
        runCurrent()

        owner.cancel()
        runCurrent()
        watchStates.release()
        advanceUntilIdle()

        assertEquals(
            WatchState(100, 4, 12_000, 1_400_000, translationId = 7, kodikSeason = 2, updatedAt = now),
            watchStates.saved.single(),
        )

        progress.report(100, 4, 20_000, 1_400_000, 7)

        assertEquals(listOf(12_000L, 20_000L), watchStates.saved.map { it.positionMs })
    }

    @Test
    fun `cancelling the reporter that owns the queue leaves it usable`() = runTest {
        watchStates.block()
        val owner = launch { progress.report(100, 4, 5_000, 1_400_000, 7) }
        runCurrent()
        owner.cancel()
        runCurrent()
        watchStates.release()

        progress.report(100, 4, 10_000, 1_400_000, 7)

        assertEquals(listOf(10_000L), watchStates.saved.map { it.positionMs })
    }
}
