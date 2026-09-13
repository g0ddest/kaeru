package app.kaeru.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvRouteTest {

    private val home = TvRoute()

    @Test
    fun `the television opens on the home screen with nothing over it`() {
        assertEquals(TvDestination.HOME, home.destination)
        assertNull(home.titleId)
    }

    @Test
    fun `choosing a destination from the drawer moves to it`() {
        assertEquals(TvRoute(TvDestination.SEARCH), home.open(TvDestination.SEARCH))
    }

    /** The drawer is on screen while a title card is open, so it has to be a way out of one. */
    @Test
    fun `choosing the destination already open closes the title card over it`() {
        val overSearch = TvRoute(TvDestination.SEARCH, titleId = 7)
        assertEquals(TvRoute(TvDestination.SEARCH), overSearch.open(TvDestination.SEARCH))
    }

    @Test
    fun `moving to another destination leaves no title card behind`() {
        val overSearch = TvRoute(TvDestination.SEARCH, titleId = 7)
        assertEquals(TvRoute(TvDestination.LIBRARY), overSearch.open(TvDestination.LIBRARY))
    }

    @Test
    fun `opening a title keeps the destination it was opened from`() {
        assertEquals(
            TvRoute(TvDestination.LIBRARY, titleId = 7),
            TvRoute(TvDestination.LIBRARY).openTitle(7),
        )
    }

    @Test
    fun `back from a title returns to the destination it was opened from`() {
        assertEquals(
            TvRoute(TvDestination.LIBRARY),
            TvRoute(TvDestination.LIBRARY, titleId = 7).back(),
        )
    }

    @Test
    fun `back from any other destination returns home`() {
        assertEquals(TvRoute(TvDestination.SETTINGS).back(), TvRoute(TvDestination.HOME))
    }

    /** Nothing left to go back to: the press belongs to the launcher, not to this app. */
    @Test
    fun `back from the home screen leaves the app`() {
        assertNull(home.back())
    }

    @Test
    fun `a title over the home screen goes back to the home screen, not out of the app`() {
        assertEquals(TvRoute(TvDestination.HOME), home.openTitle(7).back())
    }

    // ---- what a press of back means once the rail is in the picture ----

    @Test
    fun `with the rail closed, back is one step through the destinations`() {
        assertEquals(TvBack.Go(TvRoute(TvDestination.HOME)), tvBack(TvRoute(TvDestination.SEARCH), railOpen = false))
    }

    @Test
    fun `with the rail closed on the home screen, back belongs to the launcher`() {
        assertNull(tvBack(home, railOpen = false))
    }

    /** The fault this answers: back on an open rail used to leave the app from an open menu. */
    @Test
    fun `an open rail takes the press before any destination does`() {
        assertEquals(TvBack.CloseRail(TvRoute(TvDestination.HOME)), tvBack(TvRoute(TvDestination.SEARCH), railOpen = true))
    }

    /**
     * The rail cannot always close: it stays open while the screen behind it has nothing to take
     * the D-pad, which a first sync — skeletons and one sentence — does not. The press then means
     * what it would have meant with the rail closed rather than nothing at all.
     */
    @Test
    fun `a rail that cannot close falls through to the destination back would have gone to`() {
        val overLibrary = TvRoute(TvDestination.LIBRARY, titleId = 7)
        assertEquals(TvBack.CloseRail(TvRoute(TvDestination.LIBRARY)), tvBack(overLibrary, railOpen = true))
    }

    /** On the home screen there is nothing to fall through to, and back must never exit a menu. */
    @Test
    fun `an open rail on the home screen has nowhere to fall through to`() {
        assertEquals(TvBack.CloseRail(null), tvBack(home, railOpen = true))
    }

    /** Non-null whatever the fallback is, so the press is never handed to the launcher. */
    @Test
    fun `an open rail always takes the press`() {
        TvDestination.entries.forEach { destination ->
            assertNotNull(tvBack(TvRoute(destination), railOpen = true))
        }
    }
}
