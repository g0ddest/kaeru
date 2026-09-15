package app.kaeru.data.library

import android.util.Log
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "OfflineSync"

/** Long enough that a watch failing in a loop cannot spin, short enough to be unnoticed. */
private const val RETRY_DELAY_MS = 5_000L

/**
 * Watches for the network coming back and empties the queue of writes that were waiting for it.
 *
 * One watcher for the life of the process, started from the application: a marked episode has to
 * reach Shikimori whether or not the screen it was marked on is still open, and a second watcher
 * would only race the first for the same rows.
 *
 * This is where a refused write is followed up, because nothing here holds the account lock and
 * `refreshAnime` takes it. Nothing throws: a replay that fails leaves the queue where it is, and
 * the next return of the network — or the next refresh — tries again.
 */
@Singleton
class OfflineSyncStarter @Inject constructor(
    private val connectivity: Connectivity,
    private val syncer: OutboxSyncer,
    // Lazily: this is constructed while the application object is, and the library repository
    // pulls in the database and the HTTP stack behind it.
    private val library: Provider<LibraryRepository>,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            connectivity.online
                .filter { it }
                // A watch that throws is resubscribed rather than abandoned: giving up here would
                // leave the process with no way to notice the network for the rest of its life.
                .retry { error ->
                    Log.w(TAG, "Lost the connectivity watch, trying again", error)
                    delay(RETRY_DELAY_MS)
                    true
                }
                .catch { error -> Log.w(TAG, "Stopped watching for a network", error) }
                .collect { replay() }
        }
    }

    private suspend fun replay() {
        val outcome = syncer.replay()
        outcome.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Queued Shikimori writes are still waiting", error)
        }
        // The server refused these, so what this device holds for them is a claim it never
        // accepted. Re-read outside every lock — which is the whole reason this lives here.
        outcome.getOrNull()?.refused?.forEach { library.get().refreshAnime(it) }
    }
}
