package app.kaeru.domain.playback

/**
 * Whether the viewer has already been asked to let the app post the playback notification.
 *
 * A note about a question, not a setting playback obeys, which is why it is its own seam rather
 * than part of [PlaybackPreferences]. The system asks once: a refusal costs the notification and
 * its transport controls, never the video, so putting the question again on every episode would
 * be the worse bargain.
 */
interface PlaybackNotificationPrompt {
    /** True once the system has put the question, whatever the answer was. */
    suspend fun notificationsAsked(): Boolean

    /** Records that the question has been put. */
    suspend fun markNotificationsAsked()
}
