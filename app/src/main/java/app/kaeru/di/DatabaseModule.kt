package app.kaeru.di

import android.content.Context
import androidx.room.Room
import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.KaeruDatabase
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun animeDao(database: KaeruDatabase): AnimeDao = database.animeDao()

    @Provides
    fun userRateDao(database: KaeruDatabase): UserRateDao = database.userRateDao()

    @Provides
    fun watchStateDao(database: KaeruDatabase): WatchStateDao = database.watchStateDao()
}
