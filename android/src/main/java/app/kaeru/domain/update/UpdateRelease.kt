package app.kaeru.domain.update

import java.time.Instant

/**
 * A release of this app that can actually be installed: a version, a file, and what changed.
 *
 * Everything on it is already resolved. There is no «assets» list and no draft flag, because a
 * release that reaches this type has passed the one question that has assets and flags in it —
 * whether there is an APK to offer at all — and the screen above it should not be re-deciding that
 * while it draws.
 */
data class UpdateRelease(
    /** Without the leading `v`: what the screen shows, and what is compared against the install. */
    val version: String,
    /** When it was published, or null for a release GitHub gave no date for. */
    val publishedAt: Instant?,
    /** The release body as plain text: no markdown left in it, line breaks and bullets kept. */
    val notes: String,
    val apkUrl: String,
    /** The asset's own file name, which is what the download is saved as. */
    val apkName: String,
    /**
     * The asset's size as GitHub reports it.
     *
     * Checked against the file on disk after the download, which is the only integrity check
     * available: GitHub publishes no checksum for a release asset, so a truncated download is
     * caught by its length or not at all.
     */
    val sizeBytes: Long,
)

/**
 * What one completed check found, and when.
 *
 * A null [release] is the answer «nothing newer than what is installed», not «nothing was found»:
 * a check that failed is a failure and is never written down as a result. That is what lets a
 * screen opened without a network show the last real answer instead of an empty one.
 */
data class UpdateResult(
    val checkedAt: Instant,
    /** The version that was installed when the check ran, so a stale result can be recognised. */
    val installedVersion: String,
    val release: UpdateRelease?,
)
