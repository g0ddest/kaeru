package app.kaeru.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.time.Instant

@Database(
    entities = [
        AnimeEntity::class, UserRateEntity::class, WatchStateEntity::class, EpisodeProgressEntity::class,
        RateOutboxEntity::class, NotifiedEpisodeEntity::class,
    ],
    version = 5,
    // Written to `app/schemas` from version 2 on, so the next migration can be checked against
    // the schema it produces rather than only against the rows it preserves.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KaeruDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun userRateDao(): UserRateDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun episodeProgressDao(): EpisodeProgressDao
    abstract fun rateOutboxDao(): RateOutboxDao
    abstract fun notifiedEpisodeDao(): NotifiedEpisodeDao

    /**
     * The two rows one progress sample leaves behind, committed together.
     *
     * The episode's own row goes first, because it is the one «продолжить» is read from; the order
     * only matters to a reader that arrives mid-transaction, and inside one there is none.
     */
    suspend fun savePlaybackSample(watch: WatchStateEntity, progress: EpisodeProgressEntity) = withTransaction {
        episodeProgressDao().upsert(progress)
        watchStateDao().upsert(watch)
    }

    /**
     * The same two rows, taken away together: an episode the viewer has un-marked, and everything
     * after it.
     *
     * One transaction for the same reason a sample is one. Between the two writes the database
     * would say the episode has no position of its own while the pointer still stands on it with
     * one — and that is exactly the state `LibraryEntry` reads as «this episode is where you
     * stopped», which is what the un-mark is undoing.
     */
    suspend fun forgetProgressFrom(animeId: Int, episode: Int, at: Instant) = withTransaction {
        episodeProgressDao().deleteFrom(animeId, episode)
        watchStateDao().rewindFrom(animeId, episode, at)
    }

    /**
     * The rows [forgetProgressFrom] took away, put back together.
     *
     * The anime's pointer is deliberately not touched: it was rewound to the start of an episode
     * that now has a row of its own again, and that row is what every surface reads.
     */
    suspend fun restoreProgress(progress: List<EpisodeProgressEntity>) = withTransaction {
        episodeProgressDao().upsertAll(progress)
    }

    suspend fun clearAccountData() = withTransaction {
        userRateDao().deleteAll()
        watchStateDao().deleteAll()
        // Positions belong to the account the same way watch states do, and leaving them behind
        // would hand the next viewer the last one's half-watched episodes.
        episodeProgressDao().deleteAll()
        // Queued writes name one account's list and carry that account's rate ids. Replaying them
        // after a switch would write one viewer's marks onto another's list.
        rateOutboxDao().deleteAll()
        // What has already been said about a new episode is said about one account's list. Left
        // behind, it would keep the next viewer from ever hearing about the episodes it names —
        // and clearing it is also what makes the first check after a sign-in a silent one.
        notifiedEpisodeDao().deleteAll()
    }
}

/**
 * Version 2 stores the name of the track an anime is remembered in, beside its id.
 *
 * Written out rather than left to the destructive fallback the builder carries: that fallback
 * drops every table, and the table it would drop is where the viewer's playback positions live.
 * Nothing existing changes — the column is added empty, and a row that predates it simply has no
 * name to show until the next time the anime is resolved.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `watch_state` ADD COLUMN `translationTitle` TEXT")
    }
}

/**
 * Version 3 gives every episode its own position, instead of one per anime.
 *
 * `watch_state` keeps its columns and its job — which episode played last, in which track and
 * Kodik season — and the position inside each episode moves to `episode_progress`. The one row
 * an install already has is copied across, so a viewer who upgrades mid-episode comes back to the
 * minute they left rather than to the beginning.
 *
 * Only a row with something in it is copied: a position of zero says nothing that an absent row
 * does not say already, and copying it would put an episode nobody started into the season grid.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                "`animeId` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`animeId`, `episode`))",
        )
        connection.execSQL(
            "INSERT OR REPLACE INTO `episode_progress` " +
                "(`animeId`, `episode`, `positionMs`, `durationMs`, `updatedAt`) " +
                "SELECT `animeId`, `episode`, `positionMs`, `durationMs`, `updatedAt` " +
                "FROM `watch_state` WHERE `positionMs` > 0",
        )
    }
}

/**
 * Version 4 gives a mark made without a network somewhere to wait.
 *
 * Nothing existing changes: `rate_outbox` starts empty, and an install that has never been
 * offline simply never puts a row in it. The table is account-owned like the rates it speaks for,
 * so `clearAccountData` empties it alongside them.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `rate_outbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `animeId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_rate_outbox_animeId` ON `rate_outbox` (`animeId`)")
    }
}

/**
 * Version 5 gives the new-episode check somewhere to remember what it has already said.
 *
 * Nothing existing changes: `notified_episodes` starts empty, which is exactly the state
 * `NewEpisodeRule` reads as «this title has never been looked at». So the first check after an
 * upgrade writes down what is out and says nothing, and nobody is greeted by a fortnight of
 * episodes at once.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `notified_episodes` (" +
                "`animeId` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `notifiedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`animeId`, `episode`))",
        )
    }
}
