package app.kaeru.domain.playback

import kotlinx.coroutines.flow.Flow

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

    /**
     * Whether a sign-in has happened that the one-off question has not followed yet.
     *
     * Written down rather than held in a screen, because the gap it spans is exactly where a
     * screen is least safe: the login screen is replaced by the shell, and a phone turned on its
     * side in between, or a process the system reclaims, used to lose the fact entirely — leaving
     * the viewer with the setting on, no permission, and nothing left that would ever ask.
     */
    val notificationQuestionOwed: Flow<Boolean>

    suspend fun setNotificationQuestionOwed(value: Boolean)
}
