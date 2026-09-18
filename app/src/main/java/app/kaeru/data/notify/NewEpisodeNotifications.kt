package app.kaeru.data.notify

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kaeru.R
import app.kaeru.domain.notify.NewEpisode
import app.kaeru.domain.notify.NewEpisodeNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The notification that says an episode is out.
 *
 * One per title, keyed by the title's own id under a tag of this feature's own, so a second check
 * about the same show replaces its notification rather than stacking another one beside it — and
 * so it can never collide with a download's, which numbers its notifications by a different rule.
 *
 * Default importance: this makes a sound and shows on the lock screen, because it is news the
 * viewer asked to hear. It is not urgent — nothing here is a call or an alarm — so it never
 * interrupts full screen.
 */
@Singleton
class NewEpisodeNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val titleScreen: TitleScreenIntent,
) : NewEpisodeNotifier {

    @Volatile private var channelReady = false

    override suspend fun post(news: List<NewEpisode>) {
        if (news.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        // Nothing is posted when the viewer has turned notifications off at the system level.
        // `notify` would drop it, but on some builds it throws instead, and a background check is
        // not a reason to take the process down.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)
        news.forEach { episode -> publish(manager, episode) }
    }

    private fun publish(manager: NotificationManagerCompat, episode: NewEpisode) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_new_episode)
            .setContentTitle(episode.title)
            .setContentText(NewEpisodeNotificationText.episode(episode.episode))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openTitle(episode.animeId))
            .build()
        try {
            manager.notify(TAG, episode.animeId, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call.
        }
    }

    /** A tap lands on the title's own screen, where the episode list and the watch button are. */
    private fun openTitle(animeId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        animeId,
        titleScreen.create(animeId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Creates the channel if nothing has yet. Creating one that already exists with the same id is
     * a no-op, so this only ever does anything once per process.
     */
    private fun ensureChannel(manager: NotificationManagerCompat) {
        if (channelReady) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.new_episodes_channel))
                .build(),
        )
        channelReady = true
    }

    companion object {
        /** Named in the system's own notification settings, where the viewer can turn it off. */
        const val CHANNEL_ID = "new_episodes"

        /** Keeps these ids in a space of their own, away from the downloads'. */
        const val TAG = "new_episodes"
    }
}
