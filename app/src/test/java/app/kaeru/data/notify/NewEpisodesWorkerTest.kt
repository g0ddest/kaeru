package app.kaeru.data.notify

import androidx.work.ListenableWorker
import app.kaeru.domain.notify.NewEpisodeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a check's outcome means to WorkManager.
 *
 * The mapping is tested rather than the worker, and on purpose: a `CoroutineWorker` cannot be
 * built without `WorkerParameters`, which is `@RestrictTo`, and the library that can build one is
 * `work-testing` — a dependency this task is not allowed to add for the sake of three assertions.
 * Everything the worker does besides this mapping is `CheckNewEpisodes`, which has a test of its
 * own that covers the same three cases end to end.
 */
class NewEpisodesWorkerTest {

    @Test
    fun `nobody signed in is work finished, not work to try again`() {
        assertEquals(ListenableWorker.Result.success(), resultOf(NewEpisodeOutcome.NO_ACCOUNT))
    }

    @Test
    fun `a network that was not there is worth another go`() {
        assertEquals(ListenableWorker.Result.retry(), resultOf(NewEpisodeOutcome.UNREACHABLE))
    }

    @Test
    fun `a check that ran is done, whether or not it had anything to say`() {
        assertEquals(ListenableWorker.Result.success(), resultOf(NewEpisodeOutcome.CHECKED))
    }
}
