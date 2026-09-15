package app.kaeru.domain.sync

import kotlinx.coroutines.flow.Flow

/** The queue of user-rate writes that have not reached Shikimori yet. */
interface RateOutboxRepository {
    /** Every queued write, oldest first — the order the viewer made them in. */
    fun observeAll(): Flow<List<RateOp>>

    /**
     * The anime a queued write is still waiting on.
     *
     * A full refresh skips these: the server's copy of a rate is behind the viewer's until the
     * queue drains, and merging it would put the episode they just marked back where it was.
     */
    fun observePendingAnimeIds(): Flow<Set<Int>>

    /** The same set, asked once. A refresh needs the answer now, not a flow of answers. */
    suspend fun pendingAnimeIds(): Set<Int>

    suspend fun enqueue(animeId: Int, kind: RateOpKind, value: String): Long

    suspend fun remove(id: Long)

    suspend fun clear()
}
