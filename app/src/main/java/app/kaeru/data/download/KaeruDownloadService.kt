package app.kaeru.data.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
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
 * [PlatformScheduler] is what brings downloads back without the app: it hands the requirements to
 * `JobScheduler`, which restarts this service when Wi-Fi returns. It needs its own service and
 * `RECEIVE_BOOT_COMPLETED` in the manifest.
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

    override fun getDownloadManager(): DownloadManager = dependencies.downloadManager()

    override fun getScheduler(): Scheduler? = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        dependencies.notifications().progress(downloads, notMetRequirements)

    companion object {
        /** Not 0: media3 reads 0 as «run without a foreground notification». */
        private const val FOREGROUND_NOTIFICATION_ID = 0xD0

        /** The `JobScheduler` id this app's downloads are restarted under. */
        private const val JOB_ID = 0xD0

        private const val NO_CHANNEL_DESCRIPTION = 0
    }
}
