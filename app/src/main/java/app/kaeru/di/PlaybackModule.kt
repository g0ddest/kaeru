package app.kaeru.di

import androidx.media3.common.util.UnstableApi
import app.kaeru.data.kodik.KodikConstants
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.playback.RoomWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.player.DefaultPlaybackController
import app.kaeru.player.ExoPlaybackEngine
import app.kaeru.player.PlaybackController
import app.kaeru.player.PlaybackEngine
import app.kaeru.player.StreamHeaders
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The scope playback itself runs on: as long as the process, never a screen. A position
 * sample taken as an activity goes away has to outlive that activity to be written down.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackScope

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

    /**
     * Main-thread-confined on purpose: the controller and Media3 share one player, and Media3
     * refuses to be touched from anywhere but the thread its looper runs on.
     */
    @Provides
    @Singleton
    @PlaybackScope
    fun playbackScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Kodik serves a stream only to something that looks like the player page that asked for it. */
    @Provides
    @Singleton
    fun streamHeaders(): StreamHeaders = StreamHeaders(
        userAgent = KodikConstants.BROWSER_UA,
        referer = KodikConstants.PLAYER_HOST + "/",
    )

    @Provides
    @Singleton
    fun markEpisodeWatched(
        library: LibraryRepository,
        watchStates: WatchStateRepository,
        clock: Clock,
    ): MarkEpisodeWatched = MarkEpisodeWatched(library, watchStates, clock)
}

@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindings {
    // Unscoped on purpose: the implementation is already a @Singleton, so this binding hands
    // out that one instance.
    @Binds
    abstract fun watchStateRepository(impl: RoomWatchStateRepository): WatchStateRepository

    @Binds
    abstract fun playbackEngine(impl: ExoPlaybackEngine): PlaybackEngine

    @Binds
    abstract fun playbackController(impl: DefaultPlaybackController): PlaybackController
}
