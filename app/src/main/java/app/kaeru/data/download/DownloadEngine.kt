package app.kaeru.data.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import app.kaeru.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about downloads that has to happen whether or not a screen is watching.
 *
 * Three things, started once from the application:
 *
 * 1. the storage policy's network rule reaches the engine, and follows it when it changes;
 * 2. a download that fails on an expired signature is resolved again — and only a failure the
 *    refresher will not take is worth telling the viewer about;
 * 3. anything left unfinished by the last run is picked up again, under the foreground service,
 *    so a season queued yesterday does not sit still until somebody opens the app.
 */
@UnstableApi
@Singleton
class DownloadEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloads: Media3DownloadRepository,
    private val refresher: DownloadRefresher,
    private val outcomes: DownloadOutcomes,
    private val source: DownloadsSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        downloads.start(scope)
        source.addListener(Outcome(scope))
        scope.launch(io) { resumeUnfinished() }
    }

    /**
     * What the engine says as each download ends.
     *
     * A failure goes to the refresher first. Most of them are a Kodik signature that ran out
     * while the phone was asleep, and «не удалось скачать» for something about to resume by
     * itself is a notification that tells the viewer the wrong thing.
     */
    private inner class Outcome(private val scope: CoroutineScope) : DownloadsSource.Listener {
        override fun onChanged(download: Download, finalException: Exception?) {
            when (download.state) {
                Download.STATE_COMPLETED -> outcomes.completed(download)
                Download.STATE_FAILED -> scope.launch {
                    if (!refresher.refresh(download, finalException)) outcomes.failed(download)
                }
                else -> Unit
            }
        }

        override fun onRemoved(download: Download) = Unit

        override fun onIdle() = Unit
    }

    /**
     * Starts the service when, and only when, there is something left to download.
     *
     * Reading the index is what builds the engine, which opens a database and scans the cache
     * directory — hence the io dispatcher. Starting the service unconditionally would do both on
     * every launch and flash a foreground notification for nothing.
     */
    private fun resumeUnfinished() {
        val unfinished = source.current().any { !it.isTerminalState }
        if (!unfinished) return
        try {
            DownloadService.start(context, KaeruDownloadService::class.java)
        } catch (refused: IllegalStateException) {
            // The app was woken in the background, where Android will not start a service.
            // PlatformScheduler brings the downloads back when the requirements are next met.
            Log.w(TAG, "Unfinished downloads could not be resumed now", refused)
        }
    }

    private companion object {
        const val TAG = "DownloadEngine"
    }
}
