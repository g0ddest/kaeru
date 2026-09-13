package app.kaeru.ui.mobile.player

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a launch of the player is the viewer choosing an episode, or the same session coming
 * back into view. Only the first may overrule what is already playing.
 */
class LaunchIntentTest {

    private val fromHistory = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
    private val ordinary = Intent.FLAG_ACTIVITY_NEW_TASK

    @Test
    fun `a fresh launch from a screen is the viewer asking for an episode`() {
        assertTrue(isExplicitLaunch(recreated = false, intentFlags = ordinary))
    }

    @Test
    fun `a launch out of recents is the session coming back, not a new choice`() {
        assertFalse(isExplicitLaunch(recreated = false, intentFlags = ordinary or fromHistory))
    }

    @Test
    fun `an activity rebuilt from its own saved state is not a new choice either`() {
        assertFalse(isExplicitLaunch(recreated = true, intentFlags = ordinary))
    }

    @Test
    fun `and neither is a rebuilt one out of recents`() {
        assertFalse(isExplicitLaunch(recreated = true, intentFlags = ordinary or fromHistory))
    }

    @Test
    fun `an intent with no flags at all is still a choice`() {
        assertTrue(isExplicitLaunch(recreated = false, intentFlags = 0))
    }
}
