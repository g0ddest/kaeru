package app.kaeru.data.update

import app.kaeru.di.IoDispatcher
import app.kaeru.di.PlainClient
import app.kaeru.di.UpdateCacheDir
import app.kaeru.domain.update.ApkDownload
import app.kaeru.domain.update.ApkDownloads
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdateRelease
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How much has to arrive before the bar is told about it. */
private const val PROGRESS_STEP = 128L * 1024

private const val BUFFER = 64 * 1024

/**
 * The release APK, streamed onto this device.
 *
 * It goes into the cache directory rather than into files or downloads, and that is the right
 * place for it: an installer file is worth exactly one install, the system may reclaim it the
 * moment the disk is tight, and nothing in the app ever needs it again. The name is the asset's
 * own, so a second download of the same release overwrites the first rather than filling the
 * cache with copies.
 *
 * The only integrity check available is the length. GitHub publishes no checksum for a release
 * asset, so a transfer cut halfway — a proxy that closed, a captive portal that answered with a
 * login page — is caught by comparing the bytes on disk with the size the API reported, and by
 * nothing else. A file that fails that check is deleted rather than left for the installer to
 * choke on.
 *
 * The directory is injected rather than taken from a `Context`, which is what lets the whole of
 * this be tested against a temporary folder and a mock server with no Android runtime involved.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @param:PlainClient private val client: OkHttpClient,
    @param:UpdateCacheDir private val directory: File,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ApkDownloads {

    override fun download(release: UpdateRelease): Flow<ApkDownload> = flow {
        val target = File(directory, safeName(release.apkName))
        val outcome = try {
            fetch(release, target)
        } catch (failure: IOException) {
            // Nothing half-written is left behind: the next press starts from an empty file
            // rather than appending to the remains of a transfer that died in a tunnel.
            target.delete()
            ApkDownload.Failed(UpdateFailure.DOWNLOAD_FAILED)
        }
        emit(outcome)
    }.flowOn(io)

    /**
     * An extension on the collector rather than a plain function returning the outcome, because
     * the progress has to leave this loop while the loop is still running: the collector is the
     * bar, and a value returned at the end would be a bar that filled in one jump.
     */
    private suspend fun FlowCollector<ApkDownload>.fetch(
        release: UpdateRelease,
        target: File,
    ): ApkDownload {
        directory.mkdirs()
        val request = Request.Builder().url(release.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ApkDownload.Failed(UpdateFailure.DOWNLOAD_FAILED)
            var written = 0L
            var announced = 0L
            emit(ApkDownload.Running(0, release.sizeBytes))
            response.body.byteStream().use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        // A cancelled download stops here rather than at the end of the file: the
                        // screen that asked for it has gone, and the rest of thirty megabytes is
                        // being fetched for nobody.
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (written - announced >= PROGRESS_STEP) {
                            announced = written
                            emit(ApkDownload.Running(written, release.sizeBytes))
                        }
                    }
                }
            }
            // The whole point of the check: a length that does not match is a file that is not the
            // release, and handing it to the installer would be a failure with no explanation on
            // it. A release GitHub gave no size for is taken on trust, because there is nothing
            // to compare against.
            if (release.sizeBytes > 0 && written != release.sizeBytes) {
                target.delete()
                return ApkDownload.Failed(UpdateFailure.CORRUPTED)
            }
            emit(ApkDownload.Running(written, release.sizeBytes))
            return ApkDownload.Ready(target.absolutePath)
        }
    }
}

/**
 * The asset's name, reduced to something that cannot leave the cache directory.
 *
 * The name comes from a remote answer, and a remote answer containing `../` is how a download ends
 * up written somewhere nobody meant. Only the last path segment is kept, and a name with nothing
 * usable left in it falls back to a fixed one.
 */
internal fun safeName(name: String): String {
    val last = name.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = last.filter { it.isLetterOrDigit() || it in "._- " }.trim()
    return cleaned.takeIf { it.isNotEmpty() && it.trim('.').isNotEmpty() } ?: "update.apk"
}
