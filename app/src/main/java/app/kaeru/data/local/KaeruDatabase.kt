package app.kaeru.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction

@Database(
    entities = [AnimeEntity::class, UserRateEntity::class, WatchStateEntity::class],
    version = 1,
    exportSchema = false,
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
