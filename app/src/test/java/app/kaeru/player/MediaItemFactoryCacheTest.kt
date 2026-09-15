package app.kaeru.player

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.kaeru.data.download.DownloadCacheKeys
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The local player reads every byte through the download cache, under the same key the
 * downloader wrote them with. That is the whole of offline playback: without the shared cache
 * an episode on the device would still be fetched from Kodik, and without the shared key
 * factory the player would look for a name nothing was ever written under.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private val headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/")
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        database = StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext<Context>())
        cache = SimpleCache(folder.newFolder("downloads"), NoOpCacheEvictor(), database)
    }

    @After
    fun tearDown() {
        cache.release()
        database.close()
    }

    @Test
    fun `the player reads through the cache the downloader writes to`() {
        val factory = MediaItemFactory.dataSourceFactory(headers, cache)

        assertSame(cache, factory.cache)
    }

    @Test
    fun `the player names a cached segment the way the downloader named it`() {
        val factory = MediaItemFactory.dataSourceFactory(headers, cache)

        assertSame(DownloadCacheKeys, factory.cacheKeyFactory)
    }

    @Test
    fun `a manifest is still played as HLS`() {
        val item = MediaItemFactory.mediaItem("https://cdn/100/4/720.m3u8?sign=1", metadata = null)

        assertTrue(MediaItemFactory.mediaSource(item, headers, cache) is HlsMediaSource)
    }

    @Test
    fun `a re-signed link reads the bytes the downloader already wrote`() {
        // The signature expires within hours, so the address a downloaded episode is played from
        // is never the one it was downloaded from. Nothing here has a network to fall back on:
        // if the cache were keyed by the whole URL this read would go to the CDN and fail.
        val segment = "segment bytes".toByteArray()
        seed("https://cloud.kodik/100/4/720.m3u8:hls:seg-1.ts?e=1&s=old", segment)

        val read = MediaItemFactory.dataSourceFactory(headers, cache).createDataSource()
        val played = try {
            read.open(DataSpec("https://cloud.kodik/100/4/720.m3u8:hls:seg-1.ts?e=2&s=new".toUri()))
            DataSourceUtil.readToEnd(read)
        } finally {
            read.close()
        }

        assertArrayEquals(segment, played)
    }

    /** What the download engine leaves behind, written through the very same key factory. */
    private fun seed(url: String, bytes: ByteArray) {
        val writer = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(DownloadCacheKeys)
            .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) })
            .createDataSource()
        try {
            writer.open(DataSpec(url.toUri()))
            DataSourceUtil.readToEnd(writer)
        } finally {
            writer.close()
        }
    }
}
