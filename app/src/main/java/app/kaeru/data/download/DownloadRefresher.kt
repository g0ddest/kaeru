package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.ResolveEpisodeStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a fresh signature on a download whose old one expired.
 *
 * Kodik signs every URL and the signature lasts hours; a season queued overnight outlives it. The
 * fix is cheap because the cache is keyed by the address without its query
 * ([DownloadCacheKeys]): a re-signed link names the same segments, so the download resumes where
 * it stopped instead of starting over. The request keeps its id, which is how media3 merges the
 * new address onto the download already in its index.
 *
 * It is rate limited rather than unlimited, because the other thing that looks like an expired
 * signature is a source that has stopped serving this episode at all, and retrying that forever
 * would be a phone quietly fetching nothing on somebody's data plan.
 */
@UnstableApi
@Singleton
class DownloadRefresher @Inject constructor(
    private val commands: DownloadCommands,
    private val resolve: ResolveEpisodeStream,
    private val clock: Clock,
) {

    private val budget = Mutex()
    private val attempts = HashMap<String, ArrayDeque<Instant>>()

    /**
     * Tries to put this failed download back on its feet.
     *
     * @param cause the exception media3 handed its own listener. It is the only place the HTTP
     *   status is visible — by the time the row is read back, `failureReason` is a single
     *   «unknown» bit.
     * @return true when a fresh request went to the engine. False means the download stays failed,
     *   and whoever called can tell the viewer so.
     */
    suspend fun refresh(download: Download, cause: Throwable?): Boolean {
        if (download.state != Download.STATE_FAILED) return false
        if (!looksExpired(download, cause)) return false
        val key = DownloadKey.parse(download.request.id) ?: return false
        if (!claimAttempt(download.request.id)) return false

        val payload = download.payload()
        // persist = false: a background repair must not move the row that says where the viewer is.
        val stream = resolve(
            animeId = key.animeId,
            episode = key.episode,
            translationOverride = payload?.translation() ?: key.asTranslation(),
            persist = false,
        ).getOrNull() ?: return false

        // The height is part of the download's identity. A source that no longer offers it has
        // nothing to put behind this id, and quietly swapping in another height would hand the
        // player a file the viewer did not ask for.
        val url = stream.urls[key.quality] ?: return false

        commands.add(
            DownloadRequest.Builder(download.request.id, url.toUri())
                .setMimeType(download.request.mimeType)
                .setCustomCacheKey(download.request.customCacheKey)
                .setStreamKeys(download.request.streamKeys)
                .setData(download.request.data)
                .build(),
        )
        return true
    }

    /**
     * Whether this failure reads as a signature that ran out.
     *
     * Two shapes count. A 403 or 410 is the CDN saying the signature is no longer good. And any
     * failure after bytes have landed means the link did work — whatever broke it since, asking
     * for a fresh one costs one request and resumes rather than restarts. A failure at zero bytes
     * with any other status is something else entirely, and retrying it would only burn the budget.
     */
    private fun looksExpired(download: Download, cause: Throwable?): Boolean {
        if (download.bytesDownloaded > 0) return true
        return cause.responseCode() in EXPIRED_STATUSES
    }

    private fun Throwable?.responseCode(): Int? {
        var current = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    /** At most [MAX_ATTEMPTS] refreshes an hour for one download, counted on a sliding window. */
    private suspend fun claimAttempt(id: String): Boolean = budget.withLock {
        val now = clock.instant()
        val window = attempts.getOrPut(id) { ArrayDeque() }
        while (window.isNotEmpty() && Duration.between(window.first(), now) > WINDOW) {
            window.removeFirst()
        }
        if (window.size >= MAX_ATTEMPTS) return@withLock false
        window.addLast(now)
        true
    }

    /**
     * What to ask the source for when the download carries no readable blob: the id alone.
     *
     * Enough for Kodik, which resolves by id, and the season falls back to the first — a download
     * written by a build that did not store one.
     */
    private fun DownloadKey.asTranslation() =
        Translation(id = translationId, title = "", type = TranslationKind.VOICE, episodesCount = null)

    private companion object {
        val EXPIRED_STATUSES = setOf(403, 410)
        const val MAX_ATTEMPTS = 3
        val WINDOW: Duration = Duration.ofHours(1)
    }
}
