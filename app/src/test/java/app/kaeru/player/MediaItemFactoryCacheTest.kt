package app.kaeru.player

import android.content.Context
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
import app.kaeru.data.download.DownloadCacheKeys
import app.kaeru.di.PlaybackModule
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * downloader wrote them with — and writes nothing back.
 *
 * Both halves matter. Without the shared cache and the shared key factory an episode on the
 * device would still be fetched from Kodik. With writes left on, every episode watched online
 * would settle into a cache that never evicts, is never counted against the storage limit and
 * cannot be cleared from inside the app.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private val headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/")
    private val server = MockWebServer()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        database = StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext<Context>())
        cache = SimpleCache(folder.newFolder("downloads"), NoOpCacheEvictor(), database)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cache.release()
        database.close()
    }

    /** Exactly what Hilt hands the engine. */
    private fun factory(): CacheDataSource.Factory = PlaybackModule.playbackDataSource(headers, cache)

    @Test
    fun `the player reads through the cache the downloader writes to`() {
        assertSame(cache, factory().cache)
    }

    @Test
    fun `the player names a cached segment the way the downloader named it`() {
        assertSame(DownloadCacheKeys, factory().cacheKeyFactory)
    }

    @Test
    fun `a manifest is still played as HLS`() {
        val item = MediaItemFactory.mediaItem("https://cdn/100/4/720.m3u8?sign=1", metadata = null)

        assertTrue(MediaItemFactory.mediaSource(item, factory()) is HlsMediaSource)
    }

    @Test
    fun `a re-signed link reads the bytes the downloader already wrote`() {
        // The signature expires within hours, so the address a downloaded episode is played from
        // is never the one it was downloaded from. Nothing here has a network to fall back on:
        // if the cache were keyed by the whole URL this read would go to the CDN and fail.
        val segment = "segment bytes".toByteArray()
        seed("https://cloud.kodik/100/4/720.m3u8:hls:seg-1.ts?e=1&s=old", segment)

        val played = readThrough(factory(), "https://cloud.kodik/100/4/720.m3u8:hls:seg-1.ts?e=2&s=new")

        assertArrayEquals(segment, played)
    }

    @Test
    fun `nothing that was streamed is written into the download cache`() {
        // An evening of ordinary watching must leave the cache exactly as it found it. It never
        // evicts, its size is not counted against the storage limit, and nothing in the app can
        // clear what playback would put there.
        val episode = "episode bytes".toByteArray()
        server.enqueue(MockResponse().setBody(String(episode)))
        val url = server.url("/100/4/720.m3u8?sign=fresh").toString()

        val played = readThrough(factory(), url)

        assertArrayEquals(episode, played)
        assertEquals(1, server.requestCount)
        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.keys.isEmpty())
    }

    @Test
    fun `a downloaded episode still plays while the player writes nothing`() {
        // The other half of the same rule: read-only is about writes, not about reads.
        val segment = "downloaded bytes".toByteArray()
        seed("https://cloud.kodik/100/4/720.m3u8?e=1", segment)
        val before = cache.cacheSpace

        val played = readThrough(factory(), "https://cloud.kodik/100/4/720.m3u8?e=2")

        assertArrayEquals(segment, played)
        assertEquals(before, cache.cacheSpace)
    }

    private fun readThrough(factory: CacheDataSource.Factory, url: String): ByteArray {
        val source = factory.createDataSource()
        return try {
            source.open(DataSpec(url.toUri()))
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
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
