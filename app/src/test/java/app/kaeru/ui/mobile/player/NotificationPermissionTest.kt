package app.kaeru.ui.mobile.player

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who gets the notification permission prompt, and how often. */
class NotificationPermissionTest {

    private val android12 = Build.VERSION_CODES.S
    private val android13 = Build.VERSION_CODES.TIRAMISU

    @Test
    fun `a phone without the permission yet is asked`() {
        assertTrue(shouldAskForNotifications(android13, granted = false, alreadyAsked = false))
    }

    @Test
    fun `a phone that already granted it is not asked again`() {
        assertFalse(shouldAskForNotifications(android13, granted = true, alreadyAsked = false))
    }

    @Test
    fun `a refusal is taken as an answer and the question is not put again`() {
        assertFalse(shouldAskForNotifications(android13, granted = false, alreadyAsked = true))
    }

    @Test
    fun `versions that grant it at install time are never asked`() {
        assertFalse(shouldAskForNotifications(android12, granted = false, alreadyAsked = false))
    }
}
