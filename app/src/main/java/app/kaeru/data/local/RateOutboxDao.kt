package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RateOutboxDao {
    /** Returns the id the row was given, which is its place in the queue. */
    @Insert
    suspend fun insert(item: RateOutboxEntity): Long

    /** Oldest first: the order the viewer made the writes in is the order Shikimori must see them. */
    @Query("SELECT * FROM rate_outbox ORDER BY id")
    fun observeAll(): Flow<List<RateOutboxEntity>>

    @Query("SELECT * FROM rate_outbox ORDER BY id")
    suspend fun getAll(): List<RateOutboxEntity>

    @Query("SELECT DISTINCT animeId FROM rate_outbox")
    fun observePendingAnimeIds(): Flow<List<Int>>

    @Query("SELECT DISTINCT animeId FROM rate_outbox")
    suspend fun pendingAnimeIds(): List<Int>

    @Query("DELETE FROM rate_outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Callers chunk: SQLite binds at most 999 variables to one statement. */
    @Query("DELETE FROM rate_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM rate_outbox")
    suspend fun deleteAll()
}
