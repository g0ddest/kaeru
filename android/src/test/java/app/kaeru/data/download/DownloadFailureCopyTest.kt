package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What a failed download says to the viewer.
 *
 * media3 keeps one bit — «unknown» — in the row it persists, so this classification is made from
 * the exception at the moment it happens, and it decides whether the viewer goes to check their
 * Wi-Fi, clear some space, or simply wait.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DownloadFailureCopyTest {

    private fun refused(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code,
        "Refused",
        null,
        emptyMap(),
        DataSpec("https://cdn/seg.ts?sign=stale".toUri()),
        ByteArray(0),
    )

    @Test
    fun `a refused signature is an expired link`() {
        assertEquals(DownloadFailureKind.EXPIRED_LINK, DownloadFailureCopy.classify(refused(403)))
        assertEquals(DownloadFailureKind.EXPIRED_LINK, DownloadFailureCopy.classify(refused(410)))
    }

    @Test
    fun `a refused signature is found under whatever media3 wrapped it in`() {
        val wrapped = IOException("download failed", IllegalStateException("task", refused(403)))

        assertEquals(DownloadFailureKind.EXPIRED_LINK, DownloadFailureCopy.classify(wrapped))
    }

    @Test
    fun `a status that is not about the signature is not an expired link`() {
        assertEquals(DownloadFailureKind.NETWORK, DownloadFailureCopy.classify(refused(404)))
    }

    @Test
    fun `a full disk is named as one, however the platform spelt it`() {
        assertEquals(
            DownloadFailureKind.NO_SPACE,
            DownloadFailureCopy.classify(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            DownloadFailureKind.NO_SPACE,
            DownloadFailureCopy.classify(IOException("No space left on device")),
        )
    }

    @Test
    fun `an expired link beats a full disk when both are in the chain`() {
        // A signature that expired is the actionable one: the disk message here is media3's own
        // wrapper text, not the reason the transfer stopped.
        val chain = IOException("No space left on device", refused(403))

        assertEquals(DownloadFailureKind.EXPIRED_LINK, DownloadFailureCopy.classify(chain))
    }

    @Test
    fun `anything else that broke the transfer reads as the network`() {
        assertEquals(DownloadFailureKind.NETWORK, DownloadFailureCopy.classify(IOException("reset by peer")))
    }

    @Test
    fun `a failure that is not about the transfer at all admits it knows nothing`() {
        assertEquals(DownloadFailureKind.UNKNOWN, DownloadFailureCopy.classify(IllegalStateException("bug")))
        assertEquals(DownloadFailureKind.UNKNOWN, DownloadFailureCopy.classify(null))
    }

    @Test
    fun `an exception that causes itself does not hang the classifier`() {
        val looping = object : IllegalStateException("round and round") {
            override val cause: Throwable get() = this
        }

        assertEquals(DownloadFailureKind.UNKNOWN, DownloadFailureCopy.classify(looping))
    }

    @Test
    fun `each kind has its own copy`() {
        assertEquals("Ссылка устарела, попробуйте позже", DownloadFailureCopy.message(DownloadFailureKind.EXPIRED_LINK))
        assertEquals("Недостаточно места", DownloadFailureCopy.message(DownloadFailureKind.NO_SPACE))
        assertEquals("Нет связи, загрузка продолжится позже", DownloadFailureCopy.message(DownloadFailureKind.NETWORK))
        assertEquals("Не удалось скачать", DownloadFailureCopy.message(DownloadFailureKind.UNKNOWN))
    }

    @Test
    fun `a download nothing was recorded for reads as unknown`() {
        val failures = DownloadFailures()

        assertEquals("Не удалось скачать", failures.messageFor("1:1:1:720"))

        failures.record("1:1:1:720", DownloadFailureKind.NO_SPACE)
        assertEquals("Недостаточно места", failures.messageFor("1:1:1:720"))

        failures.forget("1:1:1:720")
        assertEquals("Не удалось скачать", failures.messageFor("1:1:1:720"))
    }
}
