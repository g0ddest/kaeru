package app.kaeru.data.update

import app.kaeru.domain.update.ApkDownload
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdateRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The transfer, and the one integrity check available to it.
 *
 * GitHub publishes no checksum for a release asset, so a truncated download — a proxy that closed,
 * a portal that answered with a login page — is caught by the length or not at all.
 */
class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var directory: File

    @Before
    fun setUp() {
        server.start()
        directory = tmp.newFolder("updates")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun downloader() = ApkDownloader(OkHttpClient(), directory, Dispatchers.Unconfined)

    private fun release(size: Long, name: String = "Kaeru-0.4.0.apk") = UpdateRelease(
        version = "0.4.0",
        publishedAt = null,
        notes = "",
        apkUrl = server.url("/Kaeru-0.4.0.apk").toString(),
        apkName = name,
        sizeBytes = size,
    )

    private fun body(bytes: Int) = Buffer().apply { write(ByteArray(bytes) { 7 }) }

    @Test
    fun `the file lands in the cache directory under the asset's own name`() = runTest {
        val payload = 400 * 1024
        server.enqueue(MockResponse().setBody(body(payload)))

        val stages = downloader().download(release(payload.toLong())).toList()

        val ready = stages.last() as ApkDownload.Ready
        assertEquals(File(directory, "Kaeru-0.4.0.apk").absolutePath, ready.path)
        assertEquals(payload.toLong(), File(ready.path).length())
    }

    @Test
    fun `progress is reported on the way and reaches the whole file`() = runTest {
        val payload = 600 * 1024
        server.enqueue(MockResponse().setBody(body(payload)))

        val stages = downloader().download(release(payload.toLong())).toList()

        val running = stages.filterIsInstance<ApkDownload.Running>()
        assertTrue("expected several progress steps, got ${running.size}", running.size >= 3)
        assertEquals(0L, running.first().bytes)
        assertEquals(payload.toLong(), running.last().bytes)
        assertEquals(1f, running.last().fraction, 0.0001f)
        // Monotonic: a bar that went backwards would be a bar nobody believes.
        assertEquals(running.map { it.bytes }.sorted(), running.map { it.bytes })
    }

    @Test
    fun `a file that is not the length GitHub promised is deleted rather than installed`() = runTest {
        server.enqueue(MockResponse().setBody(body(1024)))

        val stages = downloader().download(release(size = 4096)).toList()

        assertEquals(ApkDownload.Failed(UpdateFailure.CORRUPTED), stages.last())
        assertFalse(File(directory, "Kaeru-0.4.0.apk").exists())
    }

    @Test
    fun `a server that refuses is a failed download`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val stages = downloader().download(release(size = 4096)).toList()

        assertEquals(ApkDownload.Failed(UpdateFailure.DOWNLOAD_FAILED), stages.last())
    }

    @Test
    fun `a transfer that dies leaves nothing half-written behind`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(body(8 * 1024))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val stages = downloader().download(release(size = 64 * 1024)).toList()

        assertEquals(ApkDownload.Failed(UpdateFailure.DOWNLOAD_FAILED), stages.last())
        assertFalse(File(directory, "Kaeru-0.4.0.apk").exists())
    }

    /** A release GitHub reported no size for is taken on trust: there is nothing to compare with. */
    @Test
    fun `a release with no size is not checked against one`() = runTest {
        server.enqueue(MockResponse().setBody(body(2048)))

        val stages = downloader().download(release(size = 0)).toList()

        assertTrue(stages.last() is ApkDownload.Ready)
    }

    @Test
    fun `an asset name that tries to leave the directory cannot`() {
        assertEquals("evil.apk", safeName("../../evil.apk"))
        assertEquals("Kaeru-0.4.0.apk", safeName("Kaeru-0.4.0.apk"))
        assertEquals("update.apk", safeName(""))
        assertEquals("update.apk", safeName(".."))
    }
}
