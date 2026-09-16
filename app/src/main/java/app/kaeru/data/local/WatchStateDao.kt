package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface WatchStateDao {
    @Upsert
    suspend fun upsert(item: WatchStateEntity)

    @Query("SELECT * FROM watch_state")
    fun observeAll(): Flow<List<WatchStateEntity>>

    @Query("SELECT * FROM watch_state WHERE animeId = :animeId")
    fun observeByAnimeId(animeId: Int): Flow<WatchStateEntity?>

    @Query("SELECT * FROM watch_state WHERE animeId = :animeId")
    suspend fun getByAnimeId(animeId: Int): WatchStateEntity?

    /**
     * Sends the pointer back to the start of the episode it stands on, when that episode is
     * [episode] or later. An update rather than a delete: the row also carries the track and the
     * Kodik season this anime plays in, and those survive an episode being un-marked.
     */
    @Query(
        "UPDATE watch_state SET positionMs = 0, durationMs = 0, updatedAt = :updatedAt " +
            "WHERE animeId = :animeId AND episode >= :episode",
    )
    suspend fun rewindFrom(animeId: Int, episode: Int, updatedAt: Instant)

    @Query("DELETE FROM watch_state WHERE animeId = :animeId")
    suspend fun deleteByAnimeId(animeId: Int)

    @Query("DELETE FROM watch_state")
    suspend fun deleteAll()
}
