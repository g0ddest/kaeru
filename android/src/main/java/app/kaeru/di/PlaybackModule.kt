package app.kaeru.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import app.kaeru.data.download.DownloadCache
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.playback.RoomEpisodeProgressRepository
import app.kaeru.data.playback.RoomPlaybackSampleRepository
import app.kaeru.data.playback.RoomWatchStateRepository
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.DeferredRemovals
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.playback.AddStartedTitleToList
import app.kaeru.domain.playback.MarkEpisodeUnwatched
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.PrefetchTopCardStream
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.EpisodeProgressRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.PlaybackSampleRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.settings.SettingsStore
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.shared.data.kodik.KodikClient
import app.kaeru.player.CastFramework
import app.kaeru.player.DefaultPlaybackController
import app.kaeru.player.ExoPlaybackEngine
import app.kaeru.player.PlaybackController
import app.kaeru.player.PlaybackEngine
import app.kaeru.player.PlayServicesCastFramework
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
 * The engine that decodes on this device, as opposed to one playing on a Chromecast.
 * Qualified because there are now two of them, and everything except a live cast session
 * wants this one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalEngine

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
        prefs: PlaybackPreferences,
        clock: Clock,
        prefetch: StreamPrefetchCache,
    ): ResolveEpisodeStream = ResolveEpisodeStream(source, watchStates, prefs, clock, prefetch)

    /**
     * One for the process: the home screen fills it and the player empties it, and two instances
     * would mean the press still waited for a resolve that had already happened.
     */
    @Provides
    @Singleton
    fun streamPrefetchCache(clock: Clock): StreamPrefetchCache = StreamPrefetchCache(clock)

    @Provides
    @Singleton
    fun prefetchTopCardStream(
        resolve: ResolveEpisodeStream,
        cache: StreamPrefetchCache,
        watchStates: WatchStateRepository,
        downloads: DownloadRepository,
    ): PrefetchTopCardStream = PrefetchTopCardStream(resolve, cache, watchStates, downloads)

    /**
     * A single instance on purpose: the coalescing queue that keeps one position write in
     * flight at a time lives inside it, and two instances would each keep their own.
     */
    @Provides
    @Singleton
    fun watchProgress(
        watchStates: WatchStateRepository,
        samples: PlaybackSampleRepository,
        clock: Clock,
    ): WatchProgress = WatchProgress(watchStates, samples, clock)

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
        userAgent = KodikClient.BROWSER_UA,
        referer = KodikClient.PLAYER_HOST + "/",
    )

    /**
     * What the local engine reads every byte through: the download cache first, Kodik only for
     * what is not in it.
     *
     * Assembled here rather than in `player` so that nothing in that package has to know where
     * the downloads live, and by the very calls the downloader is assembled from
     * ([DownloadCache.cacheFactory], [DownloadCache.httpFactory]) so the two cannot drift apart
     * on the one thing they must agree about — what a cached segment is called. Keyed by the
     * whole URL, which is a `CacheDataSource`'s default, a re-signed Kodik link would be a file
     * nobody had ever downloaded.
     *
     * **Read-only.** A `CacheDataSource` writes what it reads unless told not to, and this cache
     * never evicts and is never measured: an evening of ordinary online watching would settle
     * several gigabytes into app storage that the «Загрузки» line cannot see, the storage limit
     * cannot count and nothing in the app can delete. Downloads are what this cache is for, and
     * they are written by the download engine alone.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` is the safety net over a cache that is not a source of truth:
     * a corrupt span drops the read through to the network instead of failing the episode.
     * Offline that fallback fails too, which is correct — there is nothing to play.
     */
    @UnstableApi
    @Provides
    @Singleton
    fun playbackDataSource(headers: StreamHeaders, cache: SimpleCache): CacheDataSource.Factory =
        DownloadCache.cacheFactory(cache, DownloadCache.httpFactory(headers.userAgent, headers.requestProperties))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Provides
    @Singleton
    fun markEpisodeWatched(
        library: LibraryRepository,
        watchStates: WatchStateRepository,
        clock: Clock,
        deleteWatchedDownloads: DeferredDownloadRemoval,
    ): MarkEpisodeWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatchedDownloads)

    /**
     * A single instance on purpose: the lock that keeps one rate create in flight at a time lives
     * inside it, and two instances would each keep their own — which is a duplicate rate on
     * Shikimori the first time a viewer changes voice while the first create is still going out.
     */
    @Provides
    @Singleton
    fun addStartedTitleToList(library: LibraryRepository): AddStartedTitleToList =
        AddStartedTitleToList(library)

    /**
     * The other direction, and deliberately not the mark's mirror image: nothing about downloads
     * is here, because an episode put back in front of the viewer is one they still want on the
     * device.
     */
    @Provides
    @Singleton
    fun markEpisodeUnwatched(
        library: LibraryRepository,
        progress: EpisodeProgressRepository,
        samples: PlaybackSampleRepository,
        suppressed: SuppressedMarks,
        clock: Clock,
        promises: DeferredRemovals,
    ): MarkEpisodeUnwatched = MarkEpisodeUnwatched(library, progress, samples, suppressed, clock, promises)

    /**
     * One instance, because it is a conversation between two things that never meet: a title screen
     * un-marking an episode and a controller deciding whether to count it. Two sets would mean the
     * controller never heard.
     */
    @Provides
    @Singleton
    fun suppressedMarks(): SuppressedMarks = SuppressedMarks()

    /**
     * One instance, because it holds what is playing: a second would defer against a target nothing
     * sets and delete a file the first is protecting.
     */
    @Provides
    @Singleton
    fun deferredDownloadRemoval(
        downloads: DownloadRepository,
        settings: SettingsStore,
        promises: DeferredRemovals,
    ): DeferredDownloadRemoval = DeferredDownloadRemoval(downloads, settings, promises)
}

@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindings {
    // Unscoped on purpose: the implementation is already a @Singleton, so this binding hands
    // out that one instance.
    @Binds
    abstract fun watchStateRepository(impl: RoomWatchStateRepository): WatchStateRepository

    /** The per-episode positions, from the same Room database and under the same account guard. */
    @Binds
    abstract fun episodeProgressRepository(impl: RoomEpisodeProgressRepository): EpisodeProgressRepository

    /** Both rows of a progress sample, in one transaction under one turn of the account lock. */
    @Binds
    abstract fun playbackSampleRepository(impl: RoomPlaybackSampleRepository): PlaybackSampleRepository

    /** The settings reader every layer above `data` sees. */
    @Binds
    abstract fun playbackPreferences(impl: AppPreferences): PlaybackPreferences

    /** The same store, as the one note the player screen has to keep between launches. */
    @Binds
    abstract fun playbackNotificationPrompt(impl: AppPreferences): PlaybackNotificationPrompt

    @Binds
    @LocalEngine
    abstract fun playbackEngine(impl: ExoPlaybackEngine): PlaybackEngine

    /** Google Cast behind its guard: the one thing in the app that touches Play services. */
    @Binds
    abstract fun castFramework(impl: PlayServicesCastFramework): CastFramework

    @Binds
    abstract fun playbackController(impl: DefaultPlaybackController): PlaybackController
}
