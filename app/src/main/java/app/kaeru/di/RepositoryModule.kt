package app.kaeru.di

import app.kaeru.data.library.ShikimoriDiscoverRepository
import app.kaeru.data.library.ShikimoriLibraryRepository
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.repository.DiscoverRepository
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

    /**
     * Stateless on purpose. The viewer's watched threshold is an argument to `build`, not a field
     * pinned here: a singleton holding one value of a setting that changes is how the hero came to
     * name one episode and start another.
     */
    @Provides
    @Singleton
    fun homeFeedBuilder(): HomeFeedBuilder = HomeFeedBuilder()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun libraryRepository(impl: ShikimoriLibraryRepository): LibraryRepository

    @Binds
    abstract fun discoverRepository(impl: ShikimoriDiscoverRepository): DiscoverRepository
}
