package app.kaeru.data.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * How a cached segment is named.
 *
 * Kodik signs every URL and the signature expires within hours, so the same segment fetched
 * twice arrives under two different addresses. Keyed by the whole URL, a cache would call the
 * second one a new file and download the episode again from zero; keyed by the address without
 * its query, a re-signed link picks up exactly where the expired one stopped. The host and the
 * path stay — two hosts serving the same path are two different files.
 *
 * The same factory has to be given to the download engine and to the player, or the player would
 * look for a key nothing was ever written under.
 */
@UnstableApi
object DownloadCacheKeys : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        dataSpec.uri.buildUpon().clearQuery().build().toString()
}

/**
 * Where downloaded episodes live, and how they are fetched.
 *
 * App-specific external storage, so no permission is needed and uninstalling the app takes the
 * episodes with it. There is no eviction: [NoOpCacheEvictor] means nothing is ever deleted
 * behind the viewer's back, and the storage limit is enforced at the front instead, by refusing
 * a new download rather than quietly dropping an old one.
 */
@UnstableApi
object DownloadCache {

    const val DIRECTORY = "downloads"

    /** External app storage when the device has it, internal storage when it does not. */
    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)

    /**
     * @param databaseProvider the one media3 database this process opens. Shared with the
     *   download index on purpose: two providers over the same file would each keep their own
     *   open helper for tables that belong together.
     */
    fun open(context: Context, databaseProvider: DatabaseProvider): SimpleCache =
        SimpleCache(directory(context), NoOpCacheEvictor(), databaseProvider)

    /**
     * Reads and writes the cache under [DownloadCacheKeys], fetching anything missing from
     * [upstream]. The engine and the player each build one of these, and they have to agree on
     * the key factory or the player would look for a name nothing was written under.
     */
    fun cacheFactory(cache: SimpleCache, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(DownloadCacheKeys)

    /**
     * The upstream the downloader fetches through.
     *
     * Exactly the headers `MediaItemFactory.mediaSource` sends, because Kodik serves a manifest
     * or a segment only to something that looks like the player page that asked for it — and a
     * downloader turned away at the CDN would look to the viewer like a broken episode. Taken as
     * plain values rather than as `StreamHeaders` so that the data layer keeps no dependency on
     * the player package; the one caller that has both is the Hilt module.
     */
    fun httpFactory(userAgent: String, requestProperties: Map<String, String>): DataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestProperties)
            .setAllowCrossProtocolRedirects(true)
}
