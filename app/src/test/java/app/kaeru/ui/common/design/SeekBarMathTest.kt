package app.kaeru.ui.common.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a finger lands on the timeline. The track is inset from both edges so the thumb can
 * overhang its ends without being clipped, which means the fraction is measured against the
 * track rather than against the component.
 */
class SeekBarMathTest {

    @Test
    fun `the left end of the track is the beginning of the episode`() {
        assertEquals(0f, seekFraction(x = 12f, width = 412f, inset = 12f), 0f)
    }

    @Test
    fun `the right end of the track is the end of the episode`() {
        assertEquals(1f, seekFraction(x = 400f, width = 412f, inset = 12f), 0f)
    }

    @Test
    fun `the middle of the track is half the episode`() {
        assertEquals(0.5f, seekFraction(x = 206f, width = 412f, inset = 12f), 1e-4f)
    }

    @Test
    fun `a quarter along the track is a quarter of the episode`() {
        assertEquals(0.25f, seekFraction(x = 109f, width = 412f, inset = 12f), 1e-4f)
    }

    @Test
    fun `a touch on the overhang left of the track still means the beginning`() {
        assertEquals(0f, seekFraction(x = 3f, width = 412f, inset = 12f), 0f)
    }

    @Test
    fun `a finger dragged off the right edge still means the end`() {
        assertEquals(1f, seekFraction(x = 980f, width = 412f, inset = 12f), 0f)
    }

    @Test
    fun `an unmeasured component answers with the beginning rather than a division by zero`() {
        assertEquals(0f, seekFraction(x = 40f, width = 0f, inset = 12f), 0f)
    }

    @Test
    fun `a component narrower than its own insets answers with the beginning`() {
        assertEquals(0f, seekFraction(x = 10f, width = 20f, inset = 12f), 0f)
    }

    @Test
    fun `without an inset the fraction is measured against the whole width`() {
        assertEquals(0.5f, seekFraction(x = 50f, width = 100f, inset = 0f), 1e-4f)
    }
}
