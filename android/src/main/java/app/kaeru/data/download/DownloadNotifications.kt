package app.kaeru.data.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.kaeru.R
import app.kaeru.domain.download.DownloadKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Told how a download ended.
 *
 * An interface so the wiring that decides *whether* to say anything — a failure the link
 * refresher is about to repair is not news — can be tested without a notification manager.
 */
@UnstableApi
interface DownloadOutcomes {
    fun completed(download: Download)
    fun failed(download: Download)
}

/**
 * The notification the download service runs in the foreground with, and the two one-off
 * notifications that say how a download ended.
 *
 * Built by hand on `NotificationCompat` rather than with media3's own
 * `DownloadNotificationHelper`, which is on the classpath but builds its strings from media3's own
 * English resources and leaves nothing to test. The copy all comes from
 * [DownloadNotificationText], which is pure, Russian, and tested.
 */
@UnstableApi
@Singleton
class DownloadNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadsScreen: DownloadsScreenIntent,
) : DownloadOutcomes {

    @Volatile private var channelReady = false

    /**
     * The foreground notification, rebuilt every second while the service runs.
     *
     * @param notMetRequirements a non-zero bit set means the engine is holding everything back
     *   because the device does not meet the policy — almost always «Только по Wi‑Fi» on mobile
     *   data. Saying so beats a progress bar frozen at nought per cent.
     */
    fun progress(downloads: List<Download>, notMetRequirements: Int): Notification {
        val running = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val items = running.map { it.item() }
        val waiting = notMetRequirements != 0
        val percent = items.map { it.percent.coerceIn(0, 100) }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
        val unknown = waiting || items.isEmpty() || running.any { it.percentDownloaded < 0f }

        return builder(android.R.drawable.stat_sys_download)
            .setContentTitle(DownloadNotificationText.TITLE)
            .setContentText(DownloadNotificationText.progress(items, waitingForNetwork = waiting))
            .setProgress(100, percent, unknown)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** «Скачано: Тайтл, 7 серия», posted once when an episode lands on the device. */
    override fun completed(download: Download) = post(
        download,
        android.R.drawable.stat_sys_download_done,
        DownloadNotificationText.completed(download.item()),
    )

    /** «Не удалось скачать Тайтл, 7 серия», posted once when the engine has given up. */
    override fun failed(download: Download) = post(
        download,
        android.R.drawable.stat_notify_error,
        DownloadNotificationText.failed(download.item()),
    )

    private fun post(download: Download, icon: Int, text: String) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        // Nothing is posted when the viewer has turned notifications off. `notify` would simply
        // drop it, but on some builds it throws instead, and a download that finished is not a
        // reason to take the process down.
        if (!manager.areNotificationsEnabled()) return
        val notification = builder(icon)
            .setContentTitle(DownloadNotificationText.TITLE)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(download.request.id.hashCode(), notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call.
        }
    }

    /**
     * Creates the channel if nothing has yet.
     *
     * media3's `DownloadService` makes the same channel in its own `onCreate`, but the two
     * one-off notifications can outlive the service — the last download finishes, the service
     * stops, and a later failure has to be sayable. Creating a channel that already exists with
     * the same id is a no-op, so this only ever runs once per process.
     */
    private fun ensureChannel() {
        if (channelReady) return
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.downloads_channel))
                .build(),
        )
        channelReady = true
    }

    private fun builder(icon: Int) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(icon)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openDownloads())
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    /** A tap lands on the downloads screen rather than wherever the app was left. */
    private fun openDownloads(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        downloadsScreen.create(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun Download.item(): DownloadNotificationText.Item {
        val payload = payload()
        val episode = DownloadKey.parse(request.id)?.episode ?: payload?.episode ?: 0
        return DownloadNotificationText.Item(
            title = payload?.title,
            episode = episode,
            percent = percentDownloaded.takeIf { it.isFinite() && it >= 0f }?.roundToInt() ?: 0,
        )
    }

    companion object {
        /** The channel media3's `DownloadService` creates for us, at low importance. */
        const val CHANNEL_ID = "downloads"
    }
}
