package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SecretTitleDao {
    @Upsert
    suspend fun upsert(item: SecretTitleEntity)

    @Query("SELECT * FROM secret_title")
    fun observeAll(): Flow<List<SecretTitleEntity>>

    @Query("SELECT * FROM secret_title")
    suspend fun all(): List<SecretTitleEntity>

    @Query("SELECT * FROM secret_title WHERE animeId = :animeId")
    suspend fun get(animeId: Int): SecretTitleEntity?

    @Query("DELETE FROM secret_title")
    suspend fun deleteAll()
}
