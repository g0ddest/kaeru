package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SkipMarksDao {
    /**
     * What is remembered about this episode at this length, or null when it has never been asked
     * about. A row with no marks in it is not null: it is the answer «nobody marked this one»,
     * and it is what the once-a-week rule is measured against.
     */
    @Query("SELECT * FROM skip_marks WHERE animeId = :animeId AND episode = :episode AND lengthSec = :lengthSec")
    suspend fun find(animeId: Int, episode: Int, lengthSec: Int): SkipMarksEntity?

    /** Replacing is right: a later answer about the same file supersedes the earlier one. */
    @Upsert
    suspend fun upsert(marks: SkipMarksEntity)
}
