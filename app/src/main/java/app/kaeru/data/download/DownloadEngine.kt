package app.kaeru.data.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.connectivity.Connectivity
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
    private val connectivity: Connectivity,
    private val commands: DownloadCommands,
    private val refresher: DownloadRefresher,
    private val outcomes: DownloadOutcomes,
    private val failures: DownloadFailures,
    private val source: DownloadsSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val started = AtomicBoolean(false)

    /** Set when the platform refused a foreground start, so [onForeground] knows there is work. */
    private val startRefused = AtomicBoolean(false)

    /** Kept from [start] so [onForeground] has somewhere to run; written once, read from anywhere. */
    @Volatile private var scope: CoroutineScope? = null

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
     * **Every line of it is inside the coroutine, including registering the listener.** This is
     * called from `Application.onCreate`, on the main thread, and the first thing that asks the
     * engine for anything is what builds it: the `DownloadManager`, its database, its handler
     * thread, and a `getExternalFilesDir` call that hits real disk. Registering a listener looks
     * free and is not — it resolves the manager to add itself to. Doing it here also means the
     * service, which media3 asks for the manager on the main thread, usually finds one built.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        this.scope = scope
        scope.launch(io) {
            source.addListener(Outcome(scope))
            settings.downloadPolicy
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    val network = if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
                    commands.setRequirements(Requirements(network))
                    if (source.current().any { !it.isTerminalState }) ensureServiceRunning()
                }
        }
        scope.launch(io) {
            connectivity.online.distinctUntilChanged().collect { online ->
                if (online) resumeNetworkFailures()
            }
        }
    }

    /**
     * Puts back every download the network took away, now that it is back.
     *
     * media3 treats a failure as terminal: a download that died when the train went into a tunnel
     * stays failed, and nothing resumes a failed row — not the requirement change, not the service,
     * not the next launch. So «Нет связи, загрузка продолжится позже» was a sentence the app had no
     * way of keeping, and the only way back was to find the title again and press «Скачать».
     *
     * Re-adding the same request is media3's own retry: the manager merges by id and puts the row
     * back in the queue. Only failures the classifier called a network failure are touched — an
     * expired signature belongs to the refresher and a full disk is not going to fix itself — and
     * only on a transition to «есть сеть», so a flapping connection costs one attempt per return
     * rather than a loop.
     */
    private fun resumeNetworkFailures() {
        val stranded = source.current().filter {
            it.state == Download.STATE_FAILED && failures.kindOf(it.request.id) == DownloadFailureKind.NETWORK
        }
        if (stranded.isEmpty()) return
        stranded.forEach { download ->
            // Not a failed download any more, whatever happens next.
            failures.forget(download.request.id)
            commands.add(download.request)
        }
        ensureServiceRunning()
    }

    /**
     * The app came back to the screen; try again if the platform turned us away while it was not.
     *
     * The one case this is for: the process was started in the background — by the platform
     * restarting the service, say — with a queue still unfinished, so [start] ran and its
     * foreground start was refused. Nothing after that would try again, because [start] runs once
     * per process and the policy flow has already emitted. An app the viewer can see is allowed
     * to promote a service, so this is the first moment it can work.
     *
     * A no-op in the ordinary case, which is every launch the viewer began themselves — and, on a
     * cold start, possibly a no-op when it should not be: [start] sets `startRefused` from inside
     * an IO coroutine, and an activity resuming before that coroutine has run finds the flag still
     * false. The next resume closes the window, and the case this exists for — a process the
     * platform started in the background, where no activity resumes at all until the viewer opens
     * the app — cannot hit it.
     */
    fun onForeground() {
        if (!startRefused.compareAndSet(true, false)) return
        val scope = scope ?: return
        scope.launch(io) {
            if (source.current().any { !it.isTerminalState }) ensureServiceRunning()
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
                failures.record(id, exhaustedKind(cause))
                outcomes.failed(download)
            }
            RefreshOutcome.DECLINED -> {
                failures.record(id, DownloadFailureCopy.classify(cause))
                outcomes.failed(download)
            }
        }
    }

    /**
     * What to say about a download the refresher tried to save and could not.
     *
     * Not «ссылка устарела» by reflex. The refresher takes on anything that failed after a byte
     * had already arrived, because a link that worked and then stopped is usually a signature that
     * ran out — but a network that dropped mid-episode looks exactly the same to it, and the
     * resolve it then attempts fails for the same reason. Telling that viewer their link expired
     * sends them to look for a problem they do not have. So the exception is asked first, and the
     * expired-link answer is the fallback for when it has nothing to say.
     */
    private fun exhaustedKind(cause: Exception?): DownloadFailureKind =
        when (val kind = DownloadFailureCopy.classify(cause)) {
            DownloadFailureKind.UNKNOWN -> DownloadFailureKind.EXPIRED_LINK
            else -> kind
        }

    /**
     * Starts the download service in the foreground.
     *
     * `DownloadService.start` would not do: it sends no foreground flag, so media3 never calls
     * `startForeground`, the queue runs inside an ordinary background service, and Android stops
     * it as soon as the app is no longer on screen.
     *
     * There is deliberately no fallback to that plain start when this is refused. On API 26 and up
     * a background app is turned away from both calls, so a fallback would be dead code nearly
     * always — and on the phone where it did get through it would produce exactly the state this
     * fix was about: a transfer inside a background service, with no notification, that Android
     * stops without telling anybody. Refusal is recorded instead, and [onForeground] tries again
     * the moment the app is somewhere the platform will allow it.
     */
    private fun ensureServiceRunning() {
        try {
            DownloadService.startForeground(context, KaeruDownloadService::class.java)
            startRefused.set(false)
        } catch (refused: IllegalStateException) {
            startRefused.set(true)
            Log.w(TAG, "Foreground start refused; downloads resume when the app is next open", refused)
        }
    }

    private companion object {
        const val TAG = "DownloadEngine"
    }
}
