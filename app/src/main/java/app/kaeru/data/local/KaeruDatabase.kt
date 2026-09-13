package app.kaeru.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [AnimeEntity::class, UserRateEntity::class, WatchStateEntity::class, EpisodeProgressEntity::class],
    version = 3,
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

    suspend fun clearAccountData() = withTransaction {
        userRateDao().deleteAll()
        watchStateDao().deleteAll()
        // Positions belong to the account the same way watch states do, and leaving them behind
        // would hand the next viewer the last one's half-watched episodes.
        episodeProgressDao().deleteAll()
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
