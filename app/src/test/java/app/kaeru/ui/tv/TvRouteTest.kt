package app.kaeru.ui.tv

import org.junit.Assert.assertEquals
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
}
