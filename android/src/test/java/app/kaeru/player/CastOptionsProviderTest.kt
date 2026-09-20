package app.kaeru.player

import app.kaeru.ui.mobile.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cast notification names its activity as a string, because playback may not import screens.
 * This is the seam that would otherwise break silently: a moved or renamed player would leave a
 * notification that opens nothing.
 */
class CastOptionsProviderTest {

    @Test
    fun `the cast notification opens the player that exists`() {
        assertEquals(PlayerActivity::class.java.name, PLAYER_ACTIVITY)
    }
}
