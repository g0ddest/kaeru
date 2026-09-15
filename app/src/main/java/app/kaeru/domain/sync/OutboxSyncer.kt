package app.kaeru.domain.sync

/** Drains the queue of offline user-rate writes into Shikimori. */
interface OutboxSyncer {
    /** Replays what is queued, one request at a time, and answers how many writes reached Shikimori. */
    suspend fun replay(): Result<Int>
}
