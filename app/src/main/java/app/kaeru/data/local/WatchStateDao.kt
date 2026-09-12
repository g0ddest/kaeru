package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchStateDao {
    @Upsert
    suspend fun upsert(item: WatchStateEntity)

    @Query("SELECT * FROM watch_state")
    fun observeAll(): Flow<List<WatchStateEntity>>

    @Query("SELECT * FROM watch_state WHERE animeId = :animeId")
    suspend fun getByAnimeId(animeId: Int): WatchStateEntity?

    @Query("DELETE FROM watch_state WHERE animeId = :animeId")
    suspend fun deleteByAnimeId(animeId: Int)
}
