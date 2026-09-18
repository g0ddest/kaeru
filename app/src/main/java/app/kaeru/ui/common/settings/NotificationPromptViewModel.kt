package app.kaeru.ui.common.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one-off system question about notifications, and what its answer means.
 *
 * It exists because «Новые серии» ships on. A viewer who never opens settings would otherwise be
 * left with a setting that says yes and a platform that says no, and would simply never hear
 * anything. So the question is put once, after a sign-in — and a refusal turns the setting off, so
 * the switch on the settings page tells the truth and there is a labelled way to change the answer.
 *
 * The note that the question has been put is [PlaybackNotificationPrompt]'s, shared with the
 * player: it is one question about one permission, and whichever screen gets there first asks it.
 */
@HiltViewModel
class NotificationPromptViewModel @Inject constructor(
    private val prompt: PlaybackNotificationPrompt,
    private val settings: SettingsStore,
) : ViewModel() {

    /**
     * Whether a sign-in is still waiting for its question, as the store holds it.
     *
     * Eagerly started so the screen has an answer to draw on its first frame rather than a false
     * that turns true underneath it.
     */
    val owed: StateFlow<Boolean> = prompt.notificationQuestionOwed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the setting still wants notifications; the caller adds the platform's own half. */
    suspend fun wanted(): Boolean = settings.newEpisodeNotifications.first()

    suspend fun alreadyAsked(): Boolean = prompt.notificationsAsked()

    /**
     * Records that the question has been put, and takes the setting down to match a refusal.
     *
     * Nothing is written on a yes beyond clearing the debt: the setting is already on, which is
     * what put the question.
     */
    fun answered(granted: Boolean) {
        viewModelScope.launch {
            prompt.markNotificationsAsked()
            if (!granted) settings.setNewEpisodeNotifications(false)
            prompt.setNotificationQuestionOwed(false)
        }
    }

    /**
     * The question was not worth putting — already granted, already asked, or a platform with no
     * such permission. Clearing it anyway, so a debt that can never be paid is not carried forever.
     */
    fun dismiss() {
        viewModelScope.launch { prompt.setNotificationQuestionOwed(false) }
    }
}
