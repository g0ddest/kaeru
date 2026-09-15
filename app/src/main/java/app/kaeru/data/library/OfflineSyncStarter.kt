package app.kaeru.data.library

import android.util.Log
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.sync.OutboxSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OfflineSync"

/**
 * Watches for the network coming back and empties the queue of writes that were waiting for it.
 *
 * One watcher for the life of the process, started from the application: a marked episode has to
 * reach Shikimori whether or not the screen it was marked on is still open, and a second watcher
 * would only race the first for the same rows.
 *
 * Nothing here throws. A replay that fails leaves the queue where it is, and the next time the
 * network returns — or the next refresh — tries again.
 */
@Singleton
class OfflineSyncStarter @Inject constructor(
    private val connectivity: Connectivity,
    private val syncer: OutboxSyncer,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            connectivity.online
                .filter { it }
                .catch { error -> Log.w(TAG, "Stopped watching for a network", error) }
                .collect {
                    syncer.replay().exceptionOrNull()?.let { error ->
                        Log.w(TAG, "Queued Shikimori writes are still waiting", error)
                    }
                }
        }
    }
}
