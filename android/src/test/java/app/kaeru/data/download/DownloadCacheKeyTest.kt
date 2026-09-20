package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Kodik signs every URL and the signature expires within hours. A cache keyed by the whole URL
 * would call a re-signed segment a different file and download the episode again from zero, so
 * the key is the part of the address that names the file and nothing else.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DownloadCacheKeyTest {

    private fun keyOf(url: String) = DownloadCacheKeys.buildCacheKey(DataSpec(url.toUri()))

    @Test
    fun `the signature is not part of the key`() {
        assertEquals("https://h/seg.ts", keyOf("https://h/seg.ts?d_sign=x&pd=1"))
    }

    @Test
    fun `two signatures of the same segment share one key`() {
        assertEquals(
            keyOf("https://cloud.kodik-storage.com/a/b/720.mp4:hls:seg-3-v1-a1.ts?e=1&s=old"),
            keyOf("https://cloud.kodik-storage.com/a/b/720.mp4:hls:seg-3-v1-a1.ts?e=2&s=new"),
        )
    }

    @Test
    fun `an address with no query is its own key`() {
        assertEquals("https://h/seg.ts", keyOf("https://h/seg.ts"))
    }

    @Test
    fun `the host and the path are kept`() {
        // Two hosts serving the same path are two different files; dropping the host would make
        // one overwrite the other in the cache.
        assertEquals("https://a.example/x/720.m3u8", keyOf("https://a.example/x/720.m3u8?t=1"))
        assertEquals("https://b.example/x/720.m3u8", keyOf("https://b.example/x/720.m3u8?t=1"))
    }

    @Test
    fun `a fragment is kept, a query is not`() {
        assertEquals("https://h/seg.ts#part", keyOf("https://h/seg.ts?sign=1#part"))
    }
}
