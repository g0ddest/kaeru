package app.kaeru.ui.common.settings

import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The one-off system question about notifications: when it is owed, and what its answer changes.
 *
 * The fact that it is owed has to outlive the composition that would put it. A phone turned on its
 * side between the login screen and the shell used to lose it, leaving the viewer with the setting
 * on, no permission, and nothing that would ever ask again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPromptViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val prompt = FakePrompt()
    private val settings = FakeSettingsStore()

    private fun viewModel() = NotificationPromptViewModel(prompt, settings)

    @Test
    fun `a question the app owes survives the screen that would have put it`() = runTest(main.dispatcher) {
        prompt.owed.value = true

        val rebuilt = viewModel()
        advanceUntilIdle()

        assertTrue(rebuilt.owed.value)
    }

    @Test
    fun `nothing is owed until somebody signs in`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.owed.value)
    }

    @Test
    fun `an answered question is owed no longer, on this phone or the next process`() = runTest(main.dispatcher) {
        prompt.owed.value = true
        val vm = viewModel()
        advanceUntilIdle()

        vm.answered(granted = true)
        advanceUntilIdle()

        assertFalse(vm.owed.value)
        assertFalse(prompt.owed.value)
        assertTrue(prompt.asked)
    }

    @Test
    fun `a refusal takes the setting down so the switch has something true to show`() = runTest(main.dispatcher) {
        prompt.owed.value = true
        val vm = viewModel()
        advanceUntilIdle()

        vm.answered(granted = false)
        advanceUntilIdle()

        assertEquals(listOf("newEpisodes=false"), settings.writes)
        assertFalse(prompt.owed.value)
    }

    @Test
    fun `a question not worth putting is dropped rather than left owed forever`() = runTest(main.dispatcher) {
        prompt.owed.value = true
        val vm = viewModel()
        advanceUntilIdle()

        vm.dismiss()
        advanceUntilIdle()

        assertFalse(prompt.owed.value)
        assertFalse(prompt.asked)
    }

    private class FakePrompt : PlaybackNotificationPrompt {
        val owed = MutableStateFlow(false)
        var asked = false

        override suspend fun notificationsAsked(): Boolean = asked

        override suspend fun markNotificationsAsked() {
            asked = true
        }

        override val notificationQuestionOwed: Flow<Boolean> = owed

        override suspend fun setNotificationQuestionOwed(value: Boolean) {
            owed.value = value
        }
    }
}
