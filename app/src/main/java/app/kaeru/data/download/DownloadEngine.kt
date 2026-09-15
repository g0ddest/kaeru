package app.kaeru.data.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *
 * The third is not a convenience. A `DownloadManager` starts paused and nothing but
 * `DownloadService.onCreate` calls `resumeDownloads()`, so on Android 12 and up — where the
 * platform scheduler media3 would otherwise use is not even constructed — starting the service
 * from here is the *only* thing that resumes a queue after the process has died.
 */
@UnstableApi
@Singleton
class DownloadEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val commands: DownloadCommands,
    private val refresher: DownloadRefresher,
    private val outcomes: DownloadOutcomes,
    private val failures: DownloadFailures,
    private val source: DownloadsSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val started = AtomicBoolean(false)

    /**
     * Applies the policy's network rule and, whenever there is work to do, makes sure a service is
     * running to do it.
     *
     * The two belong together. A requirement gates a queue that is otherwise paused, and only the
     * service resumes it, so a rule with no service does nothing and a service with the wrong rule
     * would download on mobile data. Running them in one collector means a cold start with an
     * unfinished queue does both — and a cold start with nothing to download does neither, so no
     * notification appears for a viewer who is not downloading anything.
     *
     * All of it on io: reading the index is what builds the engine, and `SimpleCache`'s
     * constructor blocks on a scan of the downloads directory. Doing it here first also means the
     * service, which media3 asks for the manager on the main thread, finds one already built.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        source.addListener(Outcome(scope))
        scope.launch(io) {
            settings.downloadPolicy
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    val network = if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
                    commands.setRequirements(Requirements(network))
                    if (source.current().any { !it.isTerminalState }) ensureServiceRunning()
                }
        }
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
                Download.STATE_COMPLETED -> {
                    failures.forget(download.request.id)
                    outcomes.completed(download)
                }
                Download.STATE_FAILED -> scope.launch {
                    report(download, finalException)
                }
                else -> Unit
            }
        }

        override fun onRemoved(download: Download) = failures.forget(download.request.id)

        override fun onIdle() = Unit

        override fun onRequirementsChanged() = Unit
    }

    /**
     * Decides whether this failure is news, and records what it was about.
     *
     * The exception exists only here — media3 persists a single «unknown» bit — so this is the
     * one moment the difference between an expired link, a full disk and a lost network can be
     * written down for the screens to read later.
     */
    private suspend fun report(download: Download, cause: Exception?) {
        val id = download.request.id
        when (refresher.refresh(download, cause)) {
            RefreshOutcome.REQUESTED -> failures.forget(id)
            RefreshOutcome.EXHAUSTED -> {
                // It was an expired link; nothing will replace it now.
                failures.record(id, DownloadFailureKind.EXPIRED_LINK)
                outcomes.failed(download)
            }
            RefreshOutcome.DECLINED -> {
                failures.record(id, DownloadFailureCopy.classify(cause))
                outcomes.failed(download)
            }
        }
    }

    /**
     * Starts the download service in the foreground.
     *
     * `DownloadService.start` would not do: it sends no foreground flag, so media3 never calls
     * `startForeground`, the queue runs inside an ordinary background service, and Android stops
     * it as soon as the app is no longer on screen. `startForeground` is refused outright when
     * the app itself is in the background on Android 12 and up — hence the catch, and hence the
     * fallback, which at least gets the work moving while the app is still up.
     */
    private fun ensureServiceRunning() {
        try {
            DownloadService.startForeground(context, KaeruDownloadService::class.java)
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "Foreground start refused; falling back to a plain start", refused)
            try {
                DownloadService.start(context, KaeruDownloadService::class.java)
            } catch (alsoRefused: IllegalStateException) {
                // Nothing left to try from the background. The next time the viewer opens the app
                // this runs again, and the queue picks up there.
                Log.w(TAG, "Downloads will resume the next time the app is opened", alsoRefused)
            }
        }
    }

    private companion object {
        const val TAG = "DownloadEngine"
    }
}
