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

    /**
     * A phone that went to the background answers every ping it finds waiting when it comes back,
     * and each answer's trip is the whole of the freeze. Half of that would land on the offset.
     */
    @Test
    fun `an answer that took seconds to come back is not a measurement of the clocks`() {
        repeat(4) { offset.exchange(offsetMs = 700, rttMs = 200) }
        // Fifteen seconds asleep: three pings answered at once, each seven seconds off.
        offset.exchange(offsetMs = 700 + 7_500, rttMs = 15_000)
        offset.exchange(offsetMs = 700 + 5_000, rttMs = 10_000)
        offset.exchange(offsetMs = 700 + 2_500, rttMs = 5_000)

        assertEquals(700, offset.offsetMs)
        assertEquals(200, offset.rttMs)

        // Right up to the line a slow packet is still a packet.
        offset.exchange(offsetMs = 700, rttMs = ClockOffset.MAX_RTT_MS)
        assertEquals(700, offset.offsetMs)
    }

    /** Clocks that only ever run forward cannot produce a negative trip; one that does is noise. */
    @Test
    fun `a trip that comes out negative is not believed either`() {
        offset.exchange(offsetMs = 100, rttMs = 40)
        offset.record(sentAt = 1_000, peerReceived = 5_000, peerSent = 6_000, receivedAt = 1_100)

        assertEquals(100, offset.offsetMs)
    }

    @Test
    fun `a clock behind this one gives a negative offset rather than a wrapped one`() {
        offset.exchange(offsetMs = -250, rttMs = 40)

        assertEquals(-250, offset.offsetMs)
        assertEquals(40, offset.rttMs)
    }
}
