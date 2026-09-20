package app.kaeru.ui.mobile.settings

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a press of «Новые серии» does, and when the system question is put unasked.
 *
 * The switch and the permission are two different things and the screen has to keep them honest:
 * a setting that says «on» while Android refuses to draw anything is a switch that lies.
 */
class NewEpisodesPermissionTest {
    private val android12 = Build.VERSION_CODES.S
    private val android13 = Build.VERSION_CODES.TIRAMISU

    @Test
    fun `turning it off never asks the system anything`() {
        assertEquals(NewEpisodesPress.TURN_OFF, pressOfNewEpisodes(false, android13, granted = false))
        assertEquals(NewEpisodesPress.TURN_OFF, pressOfNewEpisodes(false, android13, granted = true))
    }

    @Test
    fun `turning it on asks first where the platform withholds the permission`() {
        assertEquals(NewEpisodesPress.ASK_FIRST, pressOfNewEpisodes(true, android13, granted = false))
    }

    @Test
    fun `a permission already given is turned straight on`() {
        assertEquals(NewEpisodesPress.TURN_ON, pressOfNewEpisodes(true, android13, granted = true))
    }

    @Test
    fun `a platform that grants it at install has nothing to ask`() {
        assertEquals(NewEpisodesPress.TURN_ON, pressOfNewEpisodes(true, android12, granted = false))
    }

    @Test
    fun `the question is put once after a sign-in, while the setting still wants it`() {
        assertTrue(shouldOfferNotifications(android13, granted = false, alreadyAsked = false, wanted = true))
    }

    @Test
    fun `nothing is asked when the setting is already off`() {
        assertFalse(shouldOfferNotifications(android13, granted = false, alreadyAsked = false, wanted = false))
    }

    @Test
    fun `nothing is asked twice, and nothing is asked when it is already granted`() {
        assertFalse(shouldOfferNotifications(android13, granted = false, alreadyAsked = true, wanted = true))
        assertFalse(shouldOfferNotifications(android13, granted = true, alreadyAsked = false, wanted = true))
    }

    @Test
    fun `nothing is asked on a platform that has no such question`() {
        assertFalse(shouldOfferNotifications(android12, granted = false, alreadyAsked = false, wanted = true))
    }
}
