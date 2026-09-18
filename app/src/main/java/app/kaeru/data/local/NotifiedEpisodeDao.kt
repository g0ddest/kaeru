package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotifiedEpisodeDao {
    /**
     * Ignoring a conflict rather than replacing it, which is the whole of the deduplication: a
     * pair already here was already dealt with, and a replace would move its timestamp forward
     * every six hours for as long as the title is in the list.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordAll(items: List<NotifiedEpisodeEntity>)

    /**
     * Everything, because the check needs everything: for each title it asks both what the highest
     * episode seen is and whether one particular pair is there. The table holds a couple of rows
     * per title per season, so this is a few hundred rows on a long-lived install.
     */
    @Query("SELECT * FROM notified_episodes")
    suspend fun getAll(): List<NotifiedEpisodeEntity>

    @Query("DELETE FROM notified_episodes")
    suspend fun deleteAll()
}
