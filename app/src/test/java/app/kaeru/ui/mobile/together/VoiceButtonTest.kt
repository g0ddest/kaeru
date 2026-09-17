package app.kaeru.ui.mobile.together

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kaeru.domain.together.FakeVoiceCapture
import app.kaeru.domain.together.RecordedClip
import app.kaeru.ui.common.together.TogetherCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two things about this button that a person can get wrong and the code has to answer for:
 * tapping it instead of holding it, and the microphone closing without them letting go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land-notnight-mdpi")
class VoiceButtonTest {

    @get:Rule val compose = createComposeRule()

    /** The permission is asked for at the first hold; these tests are about what happens after. */
    @Before
    fun grantTheMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `a tap says how the button works instead of doing nothing at all`() {
        val capture = FakeVoiceCapture(clip = null)
        val sent = mutableListOf<Int>()
        compose.setContent {
            VoiceButton(recorder = capture, onClip = { _, ms -> sent += ms }, onDenied = {})
        }
        compose.onNodeWithContentDescription(TogetherCopy.VOICE).performClick()
        compose.onNodeWithText(TogetherCopy.VOICE_HINT).assertIsDisplayed()
        assertTrue("a tap is not a clip", sent.isEmpty())
    }

    @Test
    fun `the microphone closing by itself sends what was said`() {
        val capture = FakeVoiceCapture(clip = RecordedClip(byteArrayOf(1, 2, 3), durationMs = 30_000))
        val sent = mutableListOf<Int>()
        compose.setContent {
            VoiceButton(recorder = capture, onClip = { _, ms -> sent += ms }, onDenied = {})
        }
        // A finger still on the button, and the framework hitting its own thirty-second ceiling
        // underneath it. What the screen owes the person then is the clip, not silence.
        compose.onNodeWithContentDescription(TogetherCopy.VOICE).performTouchInput { down(center) }
        compose.waitForIdle()
        capture.open.value = false
        compose.waitForIdle()
        assertEquals(listOf(30_000), sent)

        // The finger is still down and has to come up. That lift has nothing left to stop, and
        // must not be read as a tap by somebody whose clip is already in the corner.
        compose.onNodeWithContentDescription(TogetherCopy.VOICE).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("one ceiling is one clip", listOf(30_000), sent)
        compose.onAllNodesWithText(TogetherCopy.VOICE_HINT).assertCountEquals(0)
    }

    @Test
    fun `letting go sends the clip`() {
        val capture = FakeVoiceCapture(clip = RecordedClip(byteArrayOf(7), durationMs = 2_400))
        val sent = mutableListOf<Int>()
        compose.setContent {
            VoiceButton(recorder = capture, onClip = { _, ms -> sent += ms }, onDenied = {})
        }
        compose.onNodeWithContentDescription(TogetherCopy.VOICE).performClick()
        compose.waitForIdle()
        assertEquals(listOf(2_400), sent)
    }
}
