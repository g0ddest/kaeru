package app.kaeru.data.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Four times a day, which is often enough for a weekly episode and cheap enough to forget about. */
private val PERIOD: Duration = Duration.ofHours(6)

/**
 * The check as WorkManager holds it.
 *
 * `KEEP` rather than `REPLACE`: the app start that asks for this happens every launch, and
 * replacing the work each time would restart its period — on a phone opened twice a day the check
 * would then never run at all.
 *
 * The two constraints are the two ways this could be rude. It asks Shikimori for the whole list,
 * so it waits for a network rather than failing into a retry; and it is the least urgent thing the
 * app does, so it stands aside for a battery that is nearly out.
 */
@Singleton
class WorkManagerNewEpisodesSchedule @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NewEpisodesSchedule {

    override fun enable() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<NewEpisodesWorker>(PERIOD)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NewEpisodesWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(NewEpisodesWorker.NAME)
    }
}
