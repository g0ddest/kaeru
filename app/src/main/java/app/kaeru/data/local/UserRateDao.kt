package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserRateDao {
    @Upsert
    suspend fun upsertAll(items: List<UserRateEntity>)

    @Query("SELECT * FROM user_rate")
    fun observeAll(): Flow<List<UserRateEntity>>

    @Query("SELECT * FROM user_rate WHERE animeId = :animeId")
    suspend fun getByAnimeId(animeId: Int): UserRateEntity?

    @Query("DELETE FROM user_rate")
    suspend fun deleteAll()

    @Query("DELETE FROM user_rate WHERE animeId = :animeId")
    suspend fun deleteByAnimeId(animeId: Int)

    @Transaction
    suspend fun replaceAll(items: List<UserRateEntity>) {
        deleteAll()
        upsertAll(items)
    }
}
