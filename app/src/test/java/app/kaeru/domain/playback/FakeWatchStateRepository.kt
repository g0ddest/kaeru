package app.kaeru.domain.playback

import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.WatchStateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory stand-in for the Room repository. Single-threaded by contract: every test that uses it
 * runs on one test dispatcher, so the plain counters below need no synchronization.
 */
class FakeWatchStateRepository : WatchStateRepository {
    private val rows = MutableStateFlow<Map<Int, WatchState>>(emptyMap())

    /** Saves that began, in order — including one still parked on [gate]. */
    val started = mutableListOf<WatchState>()

    /** Saves that ran to completion, in order. */
    val saved = mutableListOf<WatchState>()
    val cleared = mutableListOf<Int>()

    /** How many saves were ever in flight at once; the coalescer must never let this exceed one. */
    var peakConcurrentSaves = 0
        private set
    private var inFlight = 0

    /** While set, every save parks here, standing in for the account mutex held by a long refresh. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown by [save] once the gate lets it through, to stand in for a logout or a disk failure. */
    var failSaveWith: Throwable? = null

    fun seed(state: WatchState) = rows.update { it + (state.animeId to state) }

    fun block() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override fun observe(animeId: Int): Flow<WatchState?> = rows.map { it[animeId] }

    override suspend fun save(state: WatchState) {
        started += state
        inFlight += 1
        peakConcurrentSaves = maxOf(peakConcurrentSaves, inFlight)
        try {
            gate?.await()
            failSaveWith?.let { throw it }
            saved += state
            rows.update { it + (state.animeId to state) }
        } finally {
            inFlight -= 1
        }
    }

    override suspend fun clear(animeId: Int) {
        cleared += animeId
        rows.update { it - animeId }
    }
}
