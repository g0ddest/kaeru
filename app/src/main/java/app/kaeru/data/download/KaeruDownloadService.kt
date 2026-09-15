package app.kaeru.data.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.kaeru.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The foreground service the downloads actually run in, so leaving the app — or killing it —
 * does not stop an episode halfway.
 *
 * It owns nothing: the one [DownloadManager] in the process is reached through a Hilt entry point
 * rather than through `@AndroidEntryPoint` field injection, because media3 asks for the manager
 * from inside `DownloadService.onCreate` and an entry point is the one way to be sure it is there
 * by then, whatever order the generated `onCreate` runs in.
 *
 * There is no scheduler. media3 asks for one only below Android 12 — `DownloadService.onCreate`
 * guards the call with `SDK_INT < 31` — so on every phone this app is built for today
 * `PlatformScheduler` would be constructed for nobody, while still costing a `RECEIVE_BOOT_COMPLETED`
 * permission in the store listing and a job service in the manifest, because the job it schedules is
 * a persisted one. What resumes an unfinished queue instead, on every version, is `DownloadEngine`
 * starting this service when the app next opens.
 */
@UnstableApi
class KaeruDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadNotifications.CHANNEL_ID,
    R.string.downloads_channel,
    NO_CHANNEL_DESCRIPTION,
) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun downloadManager(): DownloadManager
        fun notifications(): DownloadNotifications
    }

    private val dependencies: Dependencies by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
    }

    /**
     * Asked for on the main thread, from inside `DownloadService.onCreate`.
     *
     * The manager is a `@Singleton`, so this builds it only if nothing has yet — and what
     * normally has is [DownloadEngine], which touches the index on io at every launch before
     * anything here can start the service. The one path that gets here first is a service the
     * platform restarts on its own, and paying for a cache scan on the main thread once, in a
     * process that has no UI up, is better than the alternative: a manager handed over half
     * built, or a `runBlocking` on the thread that would have to draw.
     */
    override fun getDownloadManager(): DownloadManager = dependencies.downloadManager()

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        dependencies.notifications().progress(downloads, notMetRequirements)

    companion object {
        /** Not 0: media3 reads 0 as «run without a foreground notification». */
        private const val FOREGROUND_NOTIFICATION_ID = 0xD0

        private const val NO_CHANNEL_DESCRIPTION = 0
    }
}
