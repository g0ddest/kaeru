package app.kaeru.di

import android.content.Context
import androidx.room.Room
import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.EpisodeProgressDao
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.MIGRATION_1_2
import app.kaeru.data.local.MIGRATION_2_3
import app.kaeru.data.local.MIGRATION_3_4
import app.kaeru.data.local.RateOutboxDao
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.WatchStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): KaeruDatabase =
        Room.databaseBuilder(context, KaeruDatabase::class.java, "kaeru.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            // Still the backstop for a version this build has no path from — a downgrade, or a
            // database left by a branch that never shipped. Everything the app can produce has a
            // migration, so nothing a viewer owns is dropped.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun animeDao(database: KaeruDatabase): AnimeDao = database.animeDao()

    @Provides
    fun userRateDao(database: KaeruDatabase): UserRateDao = database.userRateDao()

    @Provides
    fun watchStateDao(database: KaeruDatabase): WatchStateDao = database.watchStateDao()

    @Provides
    fun episodeProgressDao(database: KaeruDatabase): EpisodeProgressDao = database.episodeProgressDao()

    @Provides
    fun rateOutboxDao(database: KaeruDatabase): RateOutboxDao = database.rateOutboxDao()
}
