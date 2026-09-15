package app.kaeru.ui.mobile.player

import app.kaeru.ui.common.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the system window is told about the picture, and when the player may fold into one. */
class PictureInPictureTest {

    private val playing = PlayerUiState(
        title = "Фрирен",
        episode = 4,
        isPlaying = true,
        isBuffering = false,
        positionMs = 60_000,
        durationMs = 1_440_000,
        nextEpisodeAvailable = true,
    )

    // --- the shape of the window -------------------------------------------------------------

    @Test
    fun `an ordinary video keeps its own shape`() {
        assertEquals(PipAspect(1920, 1080), pipAspect(1920, 1080))
    }

    @Test
    fun `an old four by three episode keeps its own shape too`() {
        assertEquals(PipAspect(1440, 1080), pipAspect(1440, 1080))
    }

    @Test
    fun `a video that has not reported its size yet gets the shape most episodes have`() {
        assertEquals(PipAspect(16, 9), pipAspect(0, 0))
        assertEquals(PipAspect(16, 9), pipAspect(-1920, 1080))
    }

    @Test
    fun `a picture too wide for the platform is clipped to the widest it will accept`() {
        assertEquals(PipAspect(239, 100), pipAspect(3000, 1000))
    }

    @Test
    fun `a picture too tall for the platform is clipped to the tallest it will accept`() {
        assertEquals(PipAspect(100, 239), pipAspect(1000, 3000))
    }

    @Test
    fun `every shape the platform is offered is one it accepts`() {
        val shapes = listOf(pipAspect(3000, 1000), pipAspect(1000, 3000), pipAspect(0, 0), pipAspect(1920, 1080))

        shapes.forEach { shape ->
            assertTrue("$shape", shape.ratio in PIP_MIN_RATIO..PIP_MAX_RATIO)
        }
    }

    // --- where the picture is coming from ----------------------------------------------------

    @Test
    fun `a video shaped like its window fills it`() {
        assertEquals(PipBounds(0, 0, 2400, 1080), pipSourceBounds(2400, 1080, 1920, 864))
    }

    @Test
    fun `a taller video sits between bars at the sides`() {
        assertEquals(PipBounds(480, 0, 1920, 1080), pipSourceBounds(2400, 1080, 1440, 1080))
    }

    @Test
    fun `a wider video sits between bars above and below`() {
        assertEquals(PipBounds(0, 150, 1920, 930), pipSourceBounds(1920, 1080, 2400, 975))
    }

    @Test
    fun `a video that has not reported its size is taken to fill the window`() {
        assertEquals(PipBounds(0, 0, 2400, 1080), pipSourceBounds(2400, 1080, 0, 0))
    }

    @Test
    fun `an unmeasured window has no bounds to hint at`() {
        assertEquals(PipBounds(0, 0, 0, 0), pipSourceBounds(0, 0, 1920, 1080))
    }

    // --- when the player may fold ------------------------------------------------------------

    @Test
    fun `a playing episode folds into a window by itself`() {
        val plan = pipPlan(playing)

        assertTrue(plan.allowed)
        assertTrue(plan.autoEnter)
        assertTrue(plan.playing)
    }

    @Test
    fun `a paused episode may be put in a window but never goes on its own`() {
        val plan = pipPlan(playing.copy(isPlaying = false))

        assertTrue(plan.allowed)
        assertFalse(plan.autoEnter)
    }

    @Test
    fun `a picture on a television never folds into a window on the phone`() {
        val plan = pipPlan(playing.copy(isCasting = true))

        assertFalse(plan.allowed)
        assertFalse(plan.autoEnter)
    }

    @Test
    fun `a failed episode never folds into a window`() {
        assertFalse(pipPlan(playing.copy(errorMessage = "Нет соединения")).allowed)
    }

    @Test
    fun `a player with nothing loaded has no window to make`() {
        assertFalse(pipPlan(PlayerUiState()).allowed)
    }

    @Test
    fun `the window offers the next episode only when there is one`() {
        assertTrue(pipPlan(playing).showNext)
        assertFalse(pipPlan(playing.copy(nextEpisodeAvailable = false)).showNext)
    }
}
