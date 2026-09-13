package app.kaeru.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [AnimeEntity::class, UserRateEntity::class, WatchStateEntity::class],
    version = 2,
    // Written to `app/schemas` from this version on, so the next migration can be checked against
    // the schema it produces rather than only against the rows it preserves.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KaeruDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun userRateDao(): UserRateDao
    abstract fun watchStateDao(): WatchStateDao

    suspend fun clearAccountData() = withTransaction {
        userRateDao().deleteAll()
        watchStateDao().deleteAll()
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
