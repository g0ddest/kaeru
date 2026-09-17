package app.kaeru.domain.update

import kotlinx.coroutines.flow.Flow

/**
 * What this device knows about releases of this app.
 *
 * One record is kept: the last check that completed, whatever it found. It is both the throttle's
 * timestamp and the answer a screen opened on a train shows — those are the same fact, and keeping
 * them apart is how they come to disagree.
 */
interface UpdateRepository {

    /** The last completed check, or null on a device that has never managed one. */
    val lastResult: Flow<UpdateResult?>

    /**
     * Asks GitHub, or answers from the last check when [force] is false and one is recent enough.
     *
     * [force] is the viewer pressing «Проверить»; false is the app asking on its own at launch.
     * A failure is never written down, so the next start tries again rather than waiting out a
     * day because of one tunnel.
     */
    suspend fun check(force: Boolean): Result<UpdateResult>
}

/** Where a download of an APK has got to. */
sealed interface ApkDownload {

    /** [bytes] of [totalBytes] are on the device. Total is the asset's size, so it is always known. */
    data class Running(val bytes: Long, val totalBytes: Long) : ApkDownload {
        val fraction: Float
            get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** The whole file is on the device at [path], and its length is the one GitHub promised. */
    data class Ready(val path: String) : ApkDownload

    data class Failed(val reason: UpdateFailure) : ApkDownload
}

/** Fetching a release's APK onto this device, as the one thing the screen above needs of it. */
interface ApkDownloads {

    /**
     * Streams the asset to the device's cache, reporting progress as it goes.
     *
     * The flow ends with exactly one of [ApkDownload.Ready] or [ApkDownload.Failed]; cancelling
     * the collection cancels the transfer and leaves nothing behind.
     */
    fun download(release: UpdateRelease): Flow<ApkDownload>
}

/**
 * Handing a downloaded APK to the system, which is the only thing that can install it.
 *
 * A sideloaded app cannot install anything silently and should not try. All three of these end in
 * a system screen the viewer answers: the permission prompt, and the installer itself.
 */
interface UpdateInstaller {

    /** Whether the system will let this app start an install at all. */
    fun allowed(): Boolean

    /** Opens the system screen that grants it. False when nothing on the device answered. */
    fun requestPermission(): Boolean

    /** Hands the file at [path] to the system installer. False when nothing answered. */
    fun install(path: String): Boolean
}
