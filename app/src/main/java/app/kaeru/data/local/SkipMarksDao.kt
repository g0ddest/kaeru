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
     *
     * Near the length rather than at it: the same file answers with a length that wanders by a
     * second between a manifest and a container, and a row per wandering second would be a
     * question per wandering second. The nearest row inside [toleranceSec] is this file's row.
     * The tolerance is this table's own and reaches no further: what AniSkip is asked for still
     * has to match the file exactly, or the intervals would be some other cut's.
     */
    @Query(
        "SELECT * FROM skip_marks WHERE animeId = :animeId AND episode = :episode " +
            "AND lengthSec BETWEEN :lengthSec - :toleranceSec AND :lengthSec + :toleranceSec " +
            "ORDER BY ABS(lengthSec - :lengthSec) LIMIT 1",
    )
    suspend fun find(animeId: Int, episode: Int, lengthSec: Int, toleranceSec: Int): SkipMarksEntity?

    /** Replacing is right: a later answer about the same file supersedes the earlier one. */
    @Upsert
    suspend fun upsert(marks: SkipMarksEntity)
}
