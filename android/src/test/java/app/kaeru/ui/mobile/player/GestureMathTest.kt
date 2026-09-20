package app.kaeru.ui.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind the four things a finger can say to a video with no controls on it. */
class GestureMathTest {

    private val width = 2400f
    private val height = 1080f

    @Test
    fun `the left half of the picture is the brightness half`() {
        assertEquals(PlayerSide.LEFT, GestureMath.side(0f, width))
        assertEquals(PlayerSide.LEFT, GestureMath.side(1199f, width))
    }

    @Test
    fun `the right half of the picture is the volume half`() {
        assertEquals(PlayerSide.RIGHT, GestureMath.side(1200f, width))
        assertEquals(PlayerSide.RIGHT, GestureMath.side(width, width))
    }

    @Test
    fun `a double tap near either edge seeks, and one in the middle does not`() {
        assertEquals(DoubleTapZone.BACK, GestureMath.doubleTapZone(10f, width))
        assertEquals(DoubleTapZone.TOGGLE, GestureMath.doubleTapZone(width / 2, width))
        assertEquals(DoubleTapZone.FORWARD, GestureMath.doubleTapZone(width - 10f, width))
    }

    @Test
    fun `the seeking zones are a third of the picture each`() {
        assertEquals(DoubleTapZone.BACK, GestureMath.doubleTapZone(799f, width))
        assertEquals(DoubleTapZone.TOGGLE, GestureMath.doubleTapZone(801f, width))
        assertEquals(DoubleTapZone.TOGGLE, GestureMath.doubleTapZone(1599f, width))
        assertEquals(DoubleTapZone.FORWARD, GestureMath.doubleTapZone(1601f, width))
    }

    /** A quarter of a sweep is a quarter of the range, whichever way it goes. */
    @Test
    fun `a finger dragged up raises the level`() {
        val raised = GestureMath.adjust(current = 0.25f, dragPx = -height * GestureMath.FULL_SWEEP / 4, heightPx = height)

        assertEquals(0.5f, raised, 1e-4f)
    }

    @Test
    fun `a finger dragged down lowers it`() {
        val lowered = GestureMath.adjust(current = 0.75f, dragPx = height * GestureMath.FULL_SWEEP / 4, heightPx = height)

        assertEquals(0.5f, lowered, 1e-4f)
    }

    @Test
    fun `one full sweep of the screen covers the whole range`() {
        assertEquals(1f, GestureMath.adjust(0f, -height * GestureMath.FULL_SWEEP, height), 1e-4f)
    }

    @Test
    fun `the level never leaves its range however far the finger goes`() {
        assertEquals(1f, GestureMath.adjust(0.9f, -height * 4, height), 0f)
        assertEquals(0f, GestureMath.adjust(0.1f, height * 4, height), 0f)
    }

    @Test
    fun `an unmeasured picture leaves the level where it was`() {
        assertEquals(0.42f, GestureMath.adjust(0.42f, -500f, heightPx = 0f), 0f)
    }

    @Test
    fun `a level maps onto the steps the audio system actually has`() {
        assertEquals(0, GestureMath.step(0f, max = 15))
        assertEquals(15, GestureMath.step(1f, max = 15))
        assertEquals(8, GestureMath.step(0.5f, max = 15))
    }

    @Test
    fun `a step maps back onto a level`() {
        assertEquals(0f, GestureMath.level(0, max = 15), 0f)
        assertEquals(1f, GestureMath.level(15, max = 15), 0f)
        assertEquals(0.4f, GestureMath.level(6, max = 15), 1e-4f)
    }

    @Test
    fun `a device that reports no volume steps is not divided by`() {
        assertEquals(0f, GestureMath.level(0, max = 0), 0f)
        assertEquals(0, GestureMath.step(0.5f, max = 0))
    }
}
