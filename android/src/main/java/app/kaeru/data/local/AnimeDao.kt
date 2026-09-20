package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDao {
    @Upsert
    suspend fun upsertAll(items: List<AnimeEntity>)

    @Query("SELECT * FROM anime")
    fun observeAll(): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM anime WHERE id = :id")
    fun observeById(id: Int): Flow<AnimeEntity?>

    @Query("SELECT * FROM anime WHERE id = :id")
    suspend fun getById(id: Int): AnimeEntity?

    @Query("SELECT * FROM anime WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<AnimeEntity>
}
