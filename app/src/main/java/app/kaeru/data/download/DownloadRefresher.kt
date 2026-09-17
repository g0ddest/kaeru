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

/** What the refresher did with a failed download. */
enum class RefreshOutcome {
    /** A fresh request went to the engine; the download is on its way back. */
    REQUESTED,

    /** Not a failure a fresh link would fix, so whatever went wrong still stands. */
    DECLINED,

    /**
     * It did look like an expired link, and it could not be replaced: the hour's attempts are
     * spent, or the source would not hand one over. Nothing more will be tried by itself.
     */
    EXHAUSTED,
}

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
     * @return [RefreshOutcome.REQUESTED] when a fresh request went to the engine. The other two
     *   both mean the download stays failed, and they differ in what to tell the viewer: one is an
     *   expired link nobody will replace, the other is a failure that was never about the link.
     */
    suspend fun refresh(download: Download, cause: Throwable?): RefreshOutcome {
        if (download.state != Download.STATE_FAILED) return RefreshOutcome.DECLINED
        if (!looksExpired(download, cause)) return RefreshOutcome.DECLINED
        val key = DownloadKey.parse(download.request.id) ?: return RefreshOutcome.DECLINED
        if (!claimAttempt(download.request.id)) return RefreshOutcome.EXHAUSTED

        val payload = download.payload()
        // persist = false: a background repair must not move the row that says where the viewer is.
        // substitute = false: the file being re-signed is in one voice, and a stream in another
        // would be a different file under the same id.
        val stream = resolve(
            animeId = key.animeId,
            episode = key.episode,
            translationOverride = payload?.translation() ?: key.asTranslation(),
            persist = false,
            substitute = false,
        ).getOrNull()?.stream ?: return RefreshOutcome.EXHAUSTED

        // The height is part of the download's identity. A source that no longer offers it has
        // nothing to put behind this id, and quietly swapping in another height would hand the
        // player a file the viewer did not ask for.
        val url = stream.urls[key.quality] ?: return RefreshOutcome.EXHAUSTED

        commands.add(
            DownloadRequest.Builder(download.request.id, url.toUri())
                .setMimeType(download.request.mimeType)
                .setCustomCacheKey(download.request.customCacheKey)
                .setStreamKeys(download.request.streamKeys)
                .setData(download.request.data)
                .build(),
        )
        return RefreshOutcome.REQUESTED
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
        // Before the budget check rather than after it: the ids worth dropping are exactly the
        // ones that keep failing, and those are the calls that return early. Nothing older than
        // the window can still count against anything, and keeping it would leave one entry per
        // download ever refreshed for the life of the process.
        attempts.entries.removeAll { (other, times) ->
            other != id && (times.isEmpty() || Duration.between(times.last(), now) > WINDOW)
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
