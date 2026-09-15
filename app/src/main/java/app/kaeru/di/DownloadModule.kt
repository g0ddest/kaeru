package app.kaeru.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import app.kaeru.data.download.DownloadCache
import app.kaeru.player.StreamHeaders
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

    @Provides
    @Singleton
    fun downloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): SimpleCache = DownloadCache.open(context, databaseProvider)

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

    private const val PARALLEL_SEGMENTS = 2
    private const val MAX_PARALLEL_DOWNLOADS = 2
}
