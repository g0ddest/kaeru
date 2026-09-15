package app.kaeru.domain.sync

/**
 * What one drain of the queue achieved.
 *
 * [refused] names the anime whose queued writes Shikimori looked at and turned away. Those writes
 * are gone from the queue, and the local rate they were speaking for is now a claim the server
 * never accepted, so the caller re-reads those titles — outside whatever lock it is holding.
 */
data class ReplayOutcome(val sent: Int, val refused: Set<Int>)

/** Drains the queue of offline user-rate writes into Shikimori. */
fun interface OutboxSyncer {
    /**
     * Replays what is queued, one request at a time.
     *
     * Takes no lock and calls nothing back: the caller decides what to do about [ReplayOutcome].
     * That is deliberate — a refresh replays from inside the account lock, and a drain that
     * reached back through a lock-taking API would wedge it.
     */
    suspend fun replay(): Result<ReplayOutcome>
}
