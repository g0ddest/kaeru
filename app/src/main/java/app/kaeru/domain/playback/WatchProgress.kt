package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.EpisodeProgressRepository
import app.kaeru.domain.repository.WatchStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

/**
 * Where the viewer is inside the episode, written down often enough to survive a crash.
 *
 * The player samples every few seconds and at every pause, but a write can block for
 * seconds behind a library refresh holding the account lock. So samples are coalesced:
 * one save runs at a time, newer samples for the same anime replace the one waiting, and
 * only the newest is written. Nothing is queued up behind a slow disk, and a viewer who
 * keeps watching never loses more than the last few seconds.
 *
 * [report] returns as soon as the sample is accepted, which is immediately unless it is
 * the one that has to do the writing.
 *
 * Each accepted sample lands in two places. `episode_progress` keeps one row per episode and is
 * what «продолжить» is read from, so a viewer who opens another episode still finds this one where
 * they left it. `watch_state` keeps the single row per anime that says which episode played last,
 * in which track and Kodik season.
 */
class WatchProgress(
    private val watchStates: WatchStateRepository,
    private val episodeProgress: EpisodeProgressRepository,
    private val clock: Clock,
) {
    private data class Sample(
        val animeId: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val translationId: Int?,
        val kodikSeason: Int?,
        val at: Instant,
    )

    private val lock = Mutex()
    private val pending = LinkedHashMap<Int, Sample>()
    private var draining = false

    /**
     * @param translationId null means "I do not know", not "forget it": the track and the
     *   [kodikSeason] already remembered for this anime are kept. Both are per-anime memory
     *   that outlives the episode, and a progress sample must not be able to erase it.
     */
    suspend fun report(
        animeId: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        translationId: Int?,
        kodikSeason: Int? = null,
    ) {
        val sample = Sample(animeId, episode, positionMs, durationMs, translationId, kodikSeason, clock.instant())
        val mine = lock.withLock {
            // Per anime, so a second title being reported is never starved by a busy first one.
            pending[animeId] = sample
            if (draining) false else true.also { draining = true }
        }
        if (mine) drain()
    }

    /** Next queued sample, or null having handed the queue back to whoever reports next. */
    private suspend fun takeNext(): Sample? = lock.withLock {
        val animeId = pending.keys.firstOrNull()
        if (animeId == null) {
            // Released inside the same critical section that found the queue empty, so a sample
            // arriving now becomes the next owner's work, not an orphan.
            draining = false
            null
        } else {
            pending.remove(animeId)
        }
    }

    private suspend fun drain() {
        var owned = true
        try {
            drainQueue()
            owned = false
        } finally {
            // Cancelling the owner must not abandon what is queued behind it: that sample is
            // typically the final position of the screen whose scope just went away. It is one
            // write per anime and then the queue is empty, so this cannot run on and on.
            if (owned) withContext(NonCancellable) { drainQueue() }
        }
    }

    /** Writes queued samples until the queue runs dry and is handed back. */
    private suspend fun drainQueue() {
        while (true) {
            val next = takeNext() ?: return
            persist(next)
        }
    }

    private suspend fun persist(sample: Sample) {
        // Two rows, written independently. The per-episode row is where the viewer's place in
        // this episode lives and is the one that must survive them opening another episode, so a
        // failure to write the anime's pointer must not take it down with it, or the other way
        // round.
        bestEffort {
            episodeProgress.save(
                EpisodeProgress(
                    animeId = sample.animeId,
                    episode = sample.episode,
                    positionMs = sample.positionMs,
                    durationMs = sample.durationMs,
                    // When the viewer was there, not when the queue got to it.
                    updatedAt = sample.at,
                ),
            )
        }
        bestEffort {
            val previous = watchStates.observe(sample.animeId).first()
            val track = sample.translationId ?: previous?.translationId
            watchStates.save(
                WatchState(
                    animeId = sample.animeId,
                    episode = sample.episode,
                    positionMs = sample.positionMs,
                    durationMs = sample.durationMs,
                    translationId = track,
                    // A sample never sees the catalogue, so it cannot name a track — it can only
                    // carry forward the name already stored, and only while it is still the same
                    // track. A changed one is left nameless until the next resolve names it.
                    translationTitle = previous?.translationTitle?.takeIf { previous.translationId == track },
                    kodikSeason = sample.kodikSeason ?: previous?.kodikSeason,
                    // When the viewer was there, not when the queue got to it.
                    updatedAt = sample.at,
                ),
            )
        }
    }

    /**
     * A dropped position is worth nothing to say: the next sample is seconds away, and playback
     * must not fail because a write did.
     */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deliberately silent; see above.
        }
    }
}
