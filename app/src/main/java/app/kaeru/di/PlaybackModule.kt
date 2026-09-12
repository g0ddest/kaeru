package app.kaeru.di

import app.kaeru.data.library.AppPreferences
import app.kaeru.data.playback.RoomWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * The playback use-cases. They are domain classes and carry no injection annotations,
 * so their dependencies are named here.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun resolveEpisodeStream(
        source: EpisodeSourceProvider,
        watchStates: WatchStateRepository,
        prefs: AppPreferences,
        clock: Clock,
    ): ResolveEpisodeStream = ResolveEpisodeStream(source, watchStates, prefs, clock)

    /**
     * A single instance on purpose: the coalescing queue that keeps one position write in
     * flight at a time lives inside it, and two instances would each keep their own.
     */
    @Provides
    @Singleton
    fun watchProgress(watchStates: WatchStateRepository, clock: Clock): WatchProgress =
        WatchProgress(watchStates, clock)

    @Provides
    @Singleton
    fun markEpisodeWatched(
        library: LibraryRepository,
        watchStates: WatchStateRepository,
    ): MarkEpisodeWatched = MarkEpisodeWatched(library, watchStates)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindings {
    @Binds
    @Singleton
    abstract fun watchStateRepository(impl: RoomWatchStateRepository): WatchStateRepository
}
