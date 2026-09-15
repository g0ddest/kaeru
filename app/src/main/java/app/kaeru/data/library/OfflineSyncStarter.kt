package app.kaeru.data.library

import android.util.Log
import app.kaeru.di.ApplicationScope
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.ReplayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
 * Drains the queue of offline writes whenever there is a reason to: the network coming back, and
 * a write that has just joined the queue while the network was already up.
 *
 * One watcher for the life of the process, started from the application: a marked episode has to
 * reach Shikimori whether or not the screen it was marked on is still open, and a second watcher
 * would only race the first for the same rows.
 *
 * This is where a refused write is followed up, because nothing here holds the account lock and
 * `refreshAnime` takes it. Nothing throws: a replay that fails leaves the queue where it is, and
 * the next return of the network — or the next write, or the next refresh — tries again.
 */
@Singleton
class OfflineSyncStarter @Inject constructor(
    private val connectivity: Connectivity,
    private val syncer: OutboxSyncer,
    // Lazily: a refused write is the only thing that needs the repository, and the repository asks
    // for a drain of its own — the one link in that circle Hilt can build.
    private val library: Provider<LibraryRepository>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : ReplayRequest {
    private val started = AtomicBoolean(false)

    fun start() {
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

    /**
     * Asks for a drain now, and returns without waiting for one.
     *
     * A write made while the network is up still joins the queue when that title has older writes
     * waiting, so that the order the viewer acted in survives. Without this the mark would sit
     * there until the network next changed — which, being already up, it need never do.
     */
    override fun requestReplay() {
        scope.launch {
            // Nothing to gain from a drain with no network: it would cost one refused request per
            // mark for the whole time the viewer is offline.
            if (connectivity.online.first()) replay()
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
