package app.kaeru.data.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kaeru.R
import app.kaeru.data.image.PosterBitmaps
import app.kaeru.domain.notify.NewEpisode
import app.kaeru.domain.notify.NewEpisodeNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** «Смотреть»: the one button, and the one thing the viewer wanted when they read the line above it. */
private const val WATCH = "Смотреть"

/**
 * The notification that says an episode is out.
 *
 * One per title, keyed by the title's own id under a tag of this feature's own, so a second check
 * about the same show replaces its notification rather than stacking another one beside it — and
 * so it can never collide with a download's, which numbers its notifications by a different rule.
 *
 * Default importance: this makes a sound and shows on the lock screen, because it is news the
 * viewer asked to hear. It is not urgent — nothing here is a call or an alarm — so it never takes
 * over the screen.
 *
 * Two ways in, because there are two things somebody does with this news. The body opens the
 * title, where the episode list and the marks are; «Смотреть» starts the episode itself, with the
 * very same intent the home screen's card uses, so the notification is never a second-class way
 * into playback.
 */
@Singleton
class NewEpisodeNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val titleScreen: TitleScreenIntent,
    private val watchEpisode: WatchEpisodeIntent,
    private val posters: PosterBitmaps,
) : NewEpisodeNotifier {

    @Volatile private var channelReady = false

    /**
     * What the check asks before it looks at anything at all.
     *
     * One question about the whole app rather than about this channel: below Android 13 there is
     * no permission to lose, and from 13 on a refusal shows up here as the app having notifications
     * off. A channel the viewer has muted is a different thing and deliberately not covered — that
     * is somebody asking for quiet, not for the news to be dropped on the floor.
     */
    override fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override suspend fun post(news: List<NewEpisode>) {
        if (news.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        // Belt and braces for a permission revoked between the check's own question and this call.
        // `notify` would drop it, but on some builds it throws instead, and a background check is
        // not a reason to take the process down.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)
        news.forEach { episode -> publish(manager, episode, poster(episode)) }
        // The summary is only worth drawing over two or more. One left over from a busier check
        // would otherwise sit there claiming a group that now has a single notification in it.
        if (news.size >= 2) publish(manager, summary(news)) else cancel(manager, SUMMARY_ID)
    }

    /**
     * The poster, or nothing.
     *
     * Fetched one at a time rather than in parallel: a check that found three new episodes is
     * three small images off a disk cache, and a background worker racing them buys nothing worth
     * the concurrency. A title with no poster at all is never asked for.
     */
    private suspend fun poster(episode: NewEpisode): android.graphics.Bitmap? {
        val url = episode.posterUrl?.takeIf { it.isNotBlank() } ?: return null
        return posters.load(url)
    }

    private fun publish(manager: NotificationManagerCompat, episode: NewEpisode, poster: android.graphics.Bitmap?) {
        val notification = builder()
            .setContentTitle(episode.title)
            .setContentText(NewEpisodeNotificationText.episode(episode.episode))
            .setLargeIcon(poster)
            .setContentIntent(openTitle(episode.animeId))
            .addAction(0, WATCH, watch(episode))
            .build()
        publish(manager, notification, episode.animeId)
    }

    /**
     * The one notification that stands for all of them.
     *
     * Android draws its own header over a group on every version this app runs on, so this is
     * mostly what the collapsed group says and what a watch shows. The lines are the titles
     * themselves: a count alone would make the viewer open the shade to find out which shows.
     */
    private fun summary(news: List<NewEpisode>): Notification {
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(NewEpisodeNotificationText.TITLE)
            .setSummaryText(NewEpisodeNotificationText.titles(news.size))
        news.forEach { style.addLine(NewEpisodeNotificationText.line(it.title, it.episode)) }
        return builder()
            .setContentTitle(NewEpisodeNotificationText.TITLE)
            .setContentText(NewEpisodeNotificationText.titles(news.size))
            .setStyle(style)
            .setGroupSummary(true)
            .build()
    }

    private fun publish(manager: NotificationManagerCompat, notification: Notification, id: Int = SUMMARY_ID) {
        try {
            manager.notify(TAG, id, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call.
        }
    }

    private fun cancel(manager: NotificationManagerCompat, id: Int) = manager.cancel(TAG, id)

    private fun builder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_new_episode)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setGroup(GROUP)

    /** A tap lands on the title's own screen, where the episode list and the watch button are. */
    private fun openTitle(animeId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        animeId,
        titleScreen.create(animeId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * «Смотреть»: the episode itself, from wherever this device left it.
     *
     * A request code of its own, because the two pending intents of one title differ only in the
     * activity they name and in extras — and extras are not part of what the platform compares.
     */
    private fun watch(episode: NewEpisode): PendingIntent = PendingIntent.getActivity(
        context,
        episode.animeId.inv(),
        watchEpisode.create(episode.animeId, episode.episode),
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

        const val GROUP = "new_episodes"

        /**
         * The summary's own id. Zero is safe: every other notification here is keyed by an anime
         * id, and Shikimori numbers those from one.
         */
        private const val SUMMARY_ID = 0
    }
}
