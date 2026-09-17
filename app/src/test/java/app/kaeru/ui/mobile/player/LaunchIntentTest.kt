package app.kaeru.ui.mobile.player

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether a launch of the player is the viewer choosing an episode, or the same session coming
 * back into view. Only the first may overrule what is already playing.
 */
@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `two reads of the same intent are two different launches`() {
        val intent = Intent().putExtra("animeId", 100).putExtra("episode", 7)

        val first = readLaunch(intent, explicit = true, seq = 1)
        val second = readLaunch(intent, explicit = true, seq = 2)

        // A launch is an event, not a value. Compared by content, a viewer asking for episode 7 a
        // second time while the session sits on episode 9 would look like the launch already
        // delivered, be downgraded to a return, and attach to 9.
        assertNotEquals(first, second)
        assertEquals(100, second.animeId)
        assertEquals(7, second.episode)
        assertTrue(second.explicit)
    }

    @Test
    fun `a launch with a position carries it, and one without carries none`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val joining = readLaunch(PlayerActivity.intent(context, 100, 7, startPositionMs = 930_000), explicit = true, seq = 1)
        val ordinary = readLaunch(PlayerActivity.intent(context, 100, 7), explicit = true, seq = 2)

        assertEquals(930_000L, joining.startPositionMs)
        // None, not zero: zero is a position, and would silence the episode's own resume.
        assertNull(ordinary.startPositionMs)
    }

    @Test
    fun `a launch out of recents does not bring the join position along`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val back = readLaunch(PlayerActivity.intent(context, 100, 7, startPositionMs = 930_000), explicit = false, seq = 1)

        assertNull(back.startPositionMs)
        assertEquals(7, back.episode)
    }

    @Test
    fun `an intent with no target at all reads as one`() {
        val launch = readLaunch(Intent(), explicit = true, seq = 1)

        // What the cast notification delivers: the framework builds that intent itself.
        assertEquals(0, launch.animeId)
    }
}
