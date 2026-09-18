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
     * The rows of the titles being checked, and no others.
     *
     * The check asks two things of each title it found in the list — what the highest episode
     * seen is, and whether one particular pair is there — and titles that are not in the list
     * have nothing to say about either. Rows outlive a title's stay in «Смотрю», so the table is
     * always wider than the question.
     */
    @Query("SELECT * FROM notified_episodes WHERE animeId IN (:animeIds)")
    suspend fun getForAnime(animeIds: List<Int>): List<NotifiedEpisodeEntity>

    /**
     * Drops everything but a title's [keep] highest episodes.
     *
     * One row lands here per episode that airs while a title is in «Смотрю», and nothing but a
     * sign-out ever took one away: a show followed for years would keep a four-figure count of
     * rows that answer a question about its last two episodes. Only the highest episodes can
     * change any answer — the rule compares against the maximum and against the one pair it is
     * about to offer — so the rest are history nobody reads.
     */
    @Query(
        """
        DELETE FROM notified_episodes
        WHERE animeId = :animeId AND episode NOT IN (
            SELECT episode FROM notified_episodes WHERE animeId = :animeId ORDER BY episode DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(animeId: Int, keep: Int)

    /** Everything, for the sign-out that must leave nothing and for the tests that check it. */
    @Query("SELECT * FROM notified_episodes")
    suspend fun getAll(): List<NotifiedEpisodeEntity>

    @Query("DELETE FROM notified_episodes")
    suspend fun deleteAll()
}
