package app.kaeru.ui.mobile.player

import android.app.Application
import android.app.PendingIntent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two buttons under the floating window.
 *
 * They are the whole of what a viewer can do to a picture a few centimetres across, and the way
 * they are wired — an app-identity [PendingIntent] into a receiver registered for this app alone —
 * is the part of it Android 13 and later can quietly refuse. Proven here rather than asserted.
 */
@RunWith(RobolectricTestRunner::class)
class WindowControlsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var playPause = 0
    private var next = 0
    private val controls = WindowControls(
        context = context,
        onPlayPause = { playPause += 1 },
        onNext = { next += 1 },
    )

    @After
    fun tearDown() = controls.unregister()

    private fun press(control: Int) {
        controls.action(control).send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the play button reaches the player and nothing else does`() {
        controls.register()

        press(WindowControls.CONTROL_PLAY_PAUSE)

        assertEquals(1, playPause)
        assertEquals(0, next)
    }

    @Test
    fun `the next button reaches the player and nothing else does`() {
        controls.register()

        press(WindowControls.CONTROL_NEXT)

        assertEquals(0, playPause)
        assertEquals(1, next)
    }

    @Test
    fun `a window that has been taken down drives nothing`() {
        controls.register()
        controls.unregister()

        press(WindowControls.CONTROL_PLAY_PAUSE)

        assertEquals(0, playPause)
        assertEquals(0, next)
    }

    @Test
    fun `taking down a window twice is not an error`() {
        controls.register()
        controls.unregister()

        controls.unregister()
    }

    @Test
    fun `the two buttons are two different intents, and neither can be rewritten on the way`() {
        val play = shadowOf(controls.action(WindowControls.CONTROL_PLAY_PAUSE))
        val next = shadowOf(controls.action(WindowControls.CONTROL_NEXT))

        // Distinct request codes: one request code for both would hand the second button the
        // first one's intent, and the window's «следующая серия» would pause the episode.
        assertEquals(WindowControls.CONTROL_PLAY_PAUSE, play.requestCode)
        assertEquals(WindowControls.CONTROL_NEXT, next.requestCode)
        // Immutable, as a PendingIntent handed to the system must be from Android 12 on.
        assertTrue(play.isImmutable)
        assertTrue(next.isImmutable)
        assertTrue(play.isBroadcastIntent)
        // Addressed to this app alone, so an implicit broadcast cannot be answered elsewhere.
        assertEquals(context.packageName, play.savedIntent.`package`)
        assertEquals(
            WindowControls.CONTROL_PLAY_PAUSE,
            play.savedIntent.getIntExtra(WindowControls.EXTRA_CONTROL, 0),
        )
        assertEquals(
            WindowControls.CONTROL_NEXT,
            next.savedIntent.getIntExtra(WindowControls.EXTRA_CONTROL, 0),
        )
        // The two differ only in an extra, which a PendingIntent does not compare: without the
        // separate request codes above, asking for the second would hand back the first.
        assertTrue(play.savedIntent.filterEquals(next.savedIntent))
    }
}
