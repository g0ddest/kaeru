package app.kaeru.data.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.kaeru.domain.notify.CheckNewEpisodes
import app.kaeru.domain.notify.NewEpisodeOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The six-hourly look for an episode nobody has been told about.
 *
 * As thin as a worker can be: the whole of the decision is [CheckNewEpisodes], which knows nothing
 * about WorkManager, and this maps its answer onto the one thing WorkManager wants to hear.
 */
@HiltWorker
class NewEpisodesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: CheckNewEpisodes,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = resultOf(check.run())

    companion object {
        /** The unique work, so a second app start never adds a second check. */
        const val NAME = "new-episodes"
    }
}

/**
 * What an outcome means to WorkManager.
 *
 * Only a network that was not there is worth another go, and it is worth exactly the go
 * WorkManager's own backoff gives it. Nobody signed in is not a failure — there is no list to
 * check — and retrying it would burn the backoff for whenever somebody does sign in. Nor is a
 * platform that refuses to show anything: what changes that answer is the viewer, not a backoff.
 */
internal fun resultOf(outcome: NewEpisodeOutcome): ListenableWorker.Result = when (outcome) {
    NewEpisodeOutcome.NO_ACCOUNT -> ListenableWorker.Result.success()
    NewEpisodeOutcome.UNREACHABLE -> ListenableWorker.Result.retry()
    NewEpisodeOutcome.BLOCKED -> ListenableWorker.Result.success()
    NewEpisodeOutcome.CHECKED -> ListenableWorker.Result.success()
}
