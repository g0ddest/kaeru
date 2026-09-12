package app.kaeru.di

import app.kaeru.data.library.ShikimoriLibraryRepository
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.repository.LibraryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun homeFeedBuilder(): HomeFeedBuilder = HomeFeedBuilder()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun libraryRepository(impl: ShikimoriLibraryRepository): LibraryRepository
}
