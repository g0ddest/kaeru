package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface EpisodeProgressDao {
    @Upsert
    suspend fun upsert(item: EpisodeProgressEntity)

    /** Several at once, for an un-mark the viewer took back. */
    @Upsert
    suspend fun upsertAll(items: List<EpisodeProgressEntity>)

    /** One anime's episodes, in the order a season grid draws them. */
    @Query("SELECT * FROM episode_progress WHERE animeId = :animeId ORDER BY episode")
    fun observeByAnime(animeId: Int): Flow<List<EpisodeProgressEntity>>

    @Query("SELECT * FROM episode_progress ORDER BY animeId, episode")
    fun observeAll(): Flow<List<EpisodeProgressEntity>>

    /** One anime's positions from [episode] on, for an episode the viewer has un-marked. */
    @Query("DELETE FROM episode_progress WHERE animeId = :animeId AND episode >= :episode")
    suspend fun deleteFrom(animeId: Int, episode: Int)

    @Query("DELETE FROM episode_progress")
    suspend fun deleteAll()

    /** Every position there is, asked once: what viewing sync compares and sends. */
    @Query("SELECT * FROM episode_progress")
    suspend fun all(): List<EpisodeProgressEntity>

    @Query("SELECT * FROM episode_progress WHERE animeId = :animeId AND episode = :episode")
    suspend fun get(animeId: Int, episode: Int): EpisodeProgressEntity?

    /** A title finished on another device: its positions saved here at or before that moment. */
    @Query("DELETE FROM episode_progress WHERE animeId = :animeId AND updatedAt <= :at")
    suspend fun deleteUpTo(animeId: Int, at: Instant)
}
