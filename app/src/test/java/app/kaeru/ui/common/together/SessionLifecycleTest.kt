package app.kaeru.ui.common.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one decision behind «the session goes when the player does». */
class SessionLifecycleTest {

    @Test
    fun `backing out of the player ends the session`() {
        assertTrue(SessionLifecycle.endsSession(finishing = true, changingConfigurations = false))
    }

    @Test
    fun `turning the phone sideways does not`() {
        assertFalse(SessionLifecycle.endsSession(finishing = true, changingConfigurations = true))
    }

    @Test
    fun `going to the home screen does not`() {
        assertFalse(SessionLifecycle.endsSession(finishing = false, changingConfigurations = false))
    }

    @Test
    fun `folding the picture into a window does not, because nothing is finishing`() {
        assertFalse(SessionLifecycle.endsSession(finishing = false, changingConfigurations = false))
    }

    @Test
    fun `closing that window does, because that is the player being put down`() {
        assertTrue(SessionLifecycle.endsSession(finishing = true, changingConfigurations = false))
    }
}
