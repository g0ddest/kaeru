package app.kaeru.ui.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a press is allowed to restart the panel's linger timer. A held remote button repeats
 * twenty times a second, so the panel would otherwise recompose that often for as long as it
 * is held.
 */
class TvPanelWakeTest {

    @Test
    fun `a press restarts the timer and becomes the reference point`() {
        assertEquals(1_000L, nextWake(atMs = 1_000, lastWakeMs = 0))
    }

    @Test
    fun `a held button restarts the timer at most twice a second`() {
        assertNull(nextWake(atMs = 1_050, lastWakeMs = 1_000))
        assertNull(nextWake(atMs = 1_499, lastWakeMs = 1_000))
        assertEquals(1_500L, nextWake(atMs = 1_500, lastWakeMs = 1_000))
    }

    @Test
    fun `the autoplay offer wakes the panel without poisoning the throttle`() {
        // The countdown asks for the panel with no key event behind it. Storing a time no press
        // could ever beat is what used to leave every later press reading as "too soon", so a
        // held button stopped keeping the panel up for the rest of the session.
        val afterTheOffer = nextWake(atMs = null, lastWakeMs = 4_000)

        assertEquals(4_000L, afterTheOffer)
        assertEquals(5_000L, nextWake(atMs = 5_000, lastWakeMs = afterTheOffer!!))
    }
}
