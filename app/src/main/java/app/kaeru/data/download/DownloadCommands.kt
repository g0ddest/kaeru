package app.kaeru.data.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
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
    /** Adds, or replaces by id: media3 merges a request onto an existing download of the same id. */
    fun add(request: DownloadRequest)

    fun remove(id: String)

    fun removeAll()

    /** What the device has to satisfy before anything downloads: «Только по Wi‑Fi» and nothing else. */
    fun setRequirements(requirements: Requirements)

    /** Clears whatever stopped this download, so the engine picks it up again. */
    fun resume(id: String)
}

/**
 * The commands as media3 takes them: intents aimed at [KaeruDownloadService].
 *
 * Adding starts the service in the foreground, because the viewer has just pressed a button and
 * the platform allows a foreground app to promote a service; everything else starts it in the
 * background, where there is nothing to show yet. Every call is guarded: a phone that refuses to
 * start the service — the app was woken in the background, say — must not take the process down
 * over a queued episode, and the scheduler will pick the work up when the requirements are met.
 */
@UnstableApi
@Singleton
class Media3DownloadCommands @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DownloadCommands {

    override fun add(request: DownloadRequest) = guard {
        DownloadService.sendAddDownload(context, KaeruDownloadService::class.java, request, /* foreground = */ true)
    }

    override fun remove(id: String) = guard {
        DownloadService.sendRemoveDownload(context, KaeruDownloadService::class.java, id, /* foreground = */ false)
    }

    override fun removeAll() = guard {
        DownloadService.sendRemoveAllDownloads(context, KaeruDownloadService::class.java, /* foreground = */ false)
    }

    override fun setRequirements(requirements: Requirements) = guard {
        DownloadService.sendSetRequirements(
            context,
            KaeruDownloadService::class.java,
            requirements,
            /* foreground = */ false,
        )
    }

    override fun resume(id: String) = guard {
        DownloadService.sendSetStopReason(
            context,
            KaeruDownloadService::class.java,
            id,
            Download.STOP_REASON_NONE,
            /* foreground = */ false,
        )
    }

    private inline fun guard(command: () -> Unit) {
        try {
            command()
        } catch (refused: IllegalStateException) {
            // Android refuses to start a service from the background. Every one of these calls
            // follows something the viewer just did in a visible app, so this is the rare case —
            // and a command that never reached the engine is better than a process that died.
            Log.w(TAG, "Download service could not be started", refused)
        }
    }

    private companion object {
        const val TAG = "DownloadCommands"
    }
}
