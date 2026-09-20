package app.kaeru.data.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Everything this app tells the download engine to do.
 *
 * media3 takes these as static calls that start a service, which is not something a unit test
 * can observe. Behind this interface they are five methods a fake can record, so the repository
 * and the refresher can be tested for what they ask for rather than for what happens next.
 */
@UnstableApi
interface DownloadCommands {
    /**
     * Adds, or replaces by id: media3 merges a request onto an existing download of the same id.
     *
     * Returns whether the command was actually taken. This is the one command that starts a
     * service in the foreground, which Android refuses to a process the viewer cannot see — and a
     * caller putting a download back on the wire has to know whether it really went, or it will
     * tear up the note that says to try again.
     */
    fun add(request: DownloadRequest): Boolean

    /**
     * Removes, by id.
     *
     * Returns whether the command reached the service, for the same reason [add] does: this is a
     * plain `startService` too, which Android refuses to a process the viewer cannot see, and a
     * caller relying on the removal actually happening — forgetting a promise it made to itself —
     * has to know whether it really went, or it will tear up a note a refused command never acted
     * on.
     */
    fun remove(id: String): Boolean

    fun removeAll()

    /** What the device has to satisfy before anything downloads: «Только по Wi‑Fi» and nothing else. */
    fun setRequirements(requirements: Requirements)

    /** Clears whatever stopped this download, so the engine picks it up again. */
    fun resume(id: String)
}

/**
 * The commands as media3 takes them: intents aimed at [KaeruDownloadService].
 *
 * Adding starts the service in the foreground, because it is the one command that puts a transfer
 * on the wire and a transfer with no foreground notification is one Android stops the moment the
 * app leaves the screen. The rest start it in the background, where there is nothing to show yet.
 * Every call is guarded: a phone that refuses to start the service — the app was woken in the
 * background, say — must not take the process down over a queued episode.
 */
@UnstableApi
@Singleton
class Media3DownloadCommands @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /**
     * Asked for lazily: building the manager opens a database and scans the cache directory, and
     * neither belongs on whatever thread happens to construct this class.
     */
    private val manager: Provider<DownloadManager>,
) : DownloadCommands {

    override fun add(request: DownloadRequest): Boolean = guard {
        DownloadService.sendAddDownload(context, KaeruDownloadService::class.java, request, /* foreground = */ true)
    }

    override fun remove(id: String): Boolean = guard {
        DownloadService.sendRemoveDownload(context, KaeruDownloadService::class.java, id, /* foreground = */ false)
    }

    override fun removeAll() {
        guard {
            DownloadService.sendRemoveAllDownloads(context, KaeruDownloadService::class.java, /* foreground = */ false)
        }
    }

    /**
     * Set on the manager, which is the one command here that does not go through an intent.
     *
     * It cannot start a transfer on its own, which is the thing worth being sure about: a
     * `DownloadManager` is constructed paused — `downloadsPaused = true` in its constructor — and
     * the only caller of `resumeDownloads()` in the whole library is `DownloadService`. So a
     * requirement written here gates a queue that is not moving, and what sets it going is
     * [DownloadEngine] starting the service whenever there is something to download.
     *
     * `DownloadService.sendSetRequirements(..., foreground = true)` would do the same job and start
     * the service besides — but it would do it on every cold start, where the policy flow always
     * emits once, promoting a service to the foreground and flashing a «Загрузка серий»
     * notification at a viewer with nothing downloading. [DownloadEngine] therefore pairs this
     * call with its own foreground start, and only when the queue has something left in it.
     */
    override fun setRequirements(requirements: Requirements) {
        guard { manager.get().setRequirements(requirements) }
    }

    override fun resume(id: String) {
        guard {
            DownloadService.sendSetStopReason(
                context,
                KaeruDownloadService::class.java,
                id,
                Download.STOP_REASON_NONE,
                /* foreground = */ false,
            )
        }
    }

    /** Runs the command, and says whether the platform let it through. */
    private inline fun guard(command: () -> Unit): Boolean = try {
        command()
        true
    } catch (refused: IllegalStateException) {
        // Android refuses to start a service from the background. Most of these calls follow
        // something the viewer just did in a visible app, so this is the rare case — and a command
        // that never reached the engine is better than a process that died. Two callers act on a
        // refusal: the network re-queue, which does not follow a tap at all, and
        // DeferredDownloadRemoval.keep(), which is the whole point of this branch on remove — a
        // promise it could not keep yet must survive to the next sweep, not be torn up over
        // nothing.
        Log.w(TAG, "Download service could not be started", refused)
        false
    }

    private companion object {
        const val TAG = "DownloadCommands"
    }
}
