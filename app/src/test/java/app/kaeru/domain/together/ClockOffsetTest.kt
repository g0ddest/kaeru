package app.kaeru.domain.together

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockOffsetTest {
    private val offset = ClockOffset()

    /**
     * One exchange whose delay is the same in both directions, so the numbers going in are the
     * offset and round trip that must come out. The peer answers the instant it hears, which is
     * what makes `peerReceived` and `peerSent` the same mark.
     */
    private fun ClockOffset.exchange(offsetMs: Long, rttMs: Long) {
        val atPeer = offsetMs + rttMs / 2
        record(sentAt = 0, peerReceived = atPeer, peerSent = atPeer, receivedAt = rttMs)
    }

    @Test
    fun `four timestamps become the offset between two clocks and the trip between them`() {
        offset.record(sentAt = 0, peerReceived = 100, peerSent = 110, receivedAt = 200)

        // ((100 − 0) + (110 − 200)) / 2
        assertEquals(5, offset.offsetMs)
        // (200 − 0) − (110 − 100)
        assertEquals(190, offset.rttMs)
    }

    @Test
    fun `nothing measured yet is no offset rather than a guess`() {
        assertEquals(0, offset.offsetMs)
        assertEquals(0, offset.rttMs)
    }

    @Test
    fun `five measurements answer with the middle one, so one bad trip cannot move it`() {
        offset.exchange(offsetMs = 20, rttMs = 40)
        offset.exchange(offsetMs = 24, rttMs = 60)
        // A packet that sat in a queue somewhere: wildly off, and outvoted.
        offset.exchange(offsetMs = 900, rttMs = 1_800)
        offset.exchange(offsetMs = 18, rttMs = 30)
        offset.exchange(offsetMs = 22, rttMs = 50)

        assertEquals(22, offset.offsetMs)
        assertEquals(50, offset.rttMs)
    }

    @Test
    fun `only the last five count, so an offset that moved is followed rather than averaged over`() {
        repeat(5) { offset.exchange(offsetMs = 1_000, rttMs = 40) }
        repeat(5) { offset.exchange(offsetMs = 10, rttMs = 60) }

        assertEquals(10, offset.offsetMs)
        assertEquals(60, offset.rttMs)
    }

    @Test
    fun `before the window is full the answer is the middle of what there is`() {
        offset.exchange(offsetMs = 10, rttMs = 40)
        offset.exchange(offsetMs = 20, rttMs = 60)

        assertEquals(15, offset.offsetMs)
        assertEquals(50, offset.rttMs)
    }

    @Test
    fun `a clock behind this one gives a negative offset rather than a wrapped one`() {
        offset.exchange(offsetMs = -250, rttMs = 40)

        assertEquals(-250, offset.offsetMs)
        assertEquals(40, offset.rttMs)
    }
}
