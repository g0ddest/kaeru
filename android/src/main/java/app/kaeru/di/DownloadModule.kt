package app.kaeru.di

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import app.kaeru.data.download.DownloadCache
import app.kaeru.data.download.DownloadCommands
import app.kaeru.data.download.DownloadNotifications
import app.kaeru.data.download.DownloadOutcomes
import app.kaeru.data.download.DownloadsScreenIntent
import app.kaeru.data.download.DownloadsSource
import app.kaeru.data.download.Media3DownloadCommands
import app.kaeru.data.download.Media3DownloadRepository
import app.kaeru.data.download.Media3DownloadsSource
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.MainActivity
import app.kaeru.player.StreamHeaders
import app.kaeru.ui.mobile.Routes
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * The download engine, assembled once per process.
 *
 * It lives in its own module rather than in `OfflineModule` so that the two halves of the offline
 * work — the queue of writes waiting for a network, and the episodes already on the device — can
 * be changed without touching the same file.
 *
 * The [DownloadManager] is built from its parts rather than through the convenience constructor,
 * which would give the downloader a `CacheDataSource.Factory` with the default key factory. Kodik
 * signs every URL, so the default — key by the whole address — would treat a re-signed segment as
 * a new file and start the episode again from zero every time a signature expired.
 */
@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    /** One media3 database for the cache index and the download index alike. */
    @Provides
    @Singleton
    fun mediaDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    /**
     * Built once, and whoever asks first pays for it: `SimpleCache`'s constructor blocks on a scan
     * of the downloads directory, which on a phone holding a season is not instant. `@Singleton`
     * is the lock — Dagger's double-check builds it on one thread and parks every other caller
     * until it is there — so the thing that matters is who asks first, and `DownloadEngine.start`
     * makes sure that is an io coroutine rather than the main thread.
     */
    @Provides
    @Singleton
    fun downloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): SimpleCache = DownloadCache.open(context, databaseProvider)

    /** Built once, on whichever thread asks first; see [downloadCache], which this pulls in. */
    @Provides
    @Singleton
    fun downloadManager(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        cache: SimpleCache,
        headers: StreamHeaders,
    ): DownloadManager {
        val upstream = DownloadCache.httpFactory(headers.userAgent, headers.requestProperties)
        val downloader = DefaultDownloaderFactory(
            DownloadCache.cacheFactory(cache, upstream),
            Executors.newFixedThreadPool(PARALLEL_SEGMENTS),
        )
        return DownloadManager(context, DefaultDownloadIndex(databaseProvider), downloader).apply {
            // Two at a time: enough to keep a phone's connection busy, few enough that a queued
            // season does not open a dozen sockets to one CDN and get throttled for it.
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
        }
    }

    /**
     * Where a download notification leads. Built here rather than in `data`, which is the one
     * place that knows both the activity and the route, and keeps the data layer from naming a
     * screen.
     */
    @Provides
    @Singleton
    fun downloadsScreenIntent(@ApplicationContext context: Context): DownloadsScreenIntent =
        DownloadsScreenIntent {
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Routes.EXTRA_ROUTE, Routes.DOWNLOADS)
        }

    private const val PARALLEL_SEGMENTS = 2
    private const val MAX_PARALLEL_DOWNLOADS = 2
}

/**
 * The engine behind its two seams, and the repository behind its domain interface.
 *
 * Unscoped bindings on purpose: each implementation is already a `@Singleton`, so these hand out
 * the one instance rather than making a second.
 */
@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadBindings {
    @Binds
    abstract fun downloadCommands(impl: Media3DownloadCommands): DownloadCommands

    @Binds
    abstract fun downloadsSource(impl: Media3DownloadsSource): DownloadsSource

    @Binds
    abstract fun downloadRepository(impl: Media3DownloadRepository): DownloadRepository

    /** What the engine says when a download ends: a notification, for a viewer who left the app. */
    @Binds
    abstract fun downloadOutcomes(impl: DownloadNotifications): DownloadOutcomes
}
