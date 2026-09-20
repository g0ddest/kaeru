package app.kaeru.domain.together

/**
 * How far the friend's clock is from this one, worked out the way NTP does it.
 *
 * Four marks make one measurement: when the ping left here, when it arrived there, when the answer
 * left there, and when it arrived back. If the delay were the same in both directions the offset
 * would be exact; it is not, so the error in one measurement is half the difference between the
 * two directions — which on a mobile network is whatever queue the packet happened to land in.
 *
 * The answer is therefore the median of the last few rather than the average of them. One packet
 * that waited 900 ms in a buffer would drag an average far enough to trigger a seek; it cannot
 * move a median at all. The window is short because the thing being measured moves: a phone going
 * from Wi-Fi to mobile data changes its path, and an estimate that remembers the old one is worse
 * than no estimate.
 *
 * Written from the transport's thread and read from wherever the session runs, so every read and
 * write is under the same lock.
 */
class ClockOffset(private val window: Int = WINDOW) {

    private data class Sample(val offsetMs: Long, val rttMs: Long)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    /**
     * @param sentAt when the [TogetherMessage.Ping] left this device.
     * @param peerReceived when the peer says it arrived.
     * @param peerSent when the peer says its [TogetherMessage.Pong] left.
     * @param receivedAt when that answer arrived here.
     */
    fun record(sentAt: Long, peerReceived: Long, peerSent: Long, receivedAt: Long) {
        val sample = Sample(
            offsetMs = ((peerReceived - sentAt) + (peerSent - receivedAt)) / 2,
            rttMs = (receivedAt - sentAt) - (peerSent - peerReceived),
        )
        // A round trip that took seconds is not a measurement of the clocks, it is a measurement
        // of something else: an answer that sat in a frozen phone — iOS stops the whole process
        // in the background and the pings queue up — or in a socket that was being redialled.
        // Half of that wait lands on the offset, and three such answers in a row would outvote
        // the median. The error in an honest sample is at most half its round trip, so a cap on
        // the trip is a cap on the error; past it the sample says nothing worth keeping.
        if (sample.rttMs < 0 || sample.rttMs > MAX_RTT_MS) return
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > window) samples.removeFirst()
        }
    }

    /** Positive when the friend's clock reads ahead of this one. Zero until something is measured. */
    val offsetMs: Long get() = median { it.offsetMs }

    /** The round trip, for showing a connection as good or poor. Zero until something is measured. */
    val rttMs: Long get() = median { it.rttMs }

    private inline fun median(of: (Sample) -> Long): Long {
        val sorted = synchronized(lock) { samples.map(of) }.sorted()
        if (sorted.isEmpty()) return 0
        // With an even count there is no middle, so the two either side of it are averaged; with
        // an odd one both indices are the same element and this is the element itself.
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    companion object {
        /** Five is enough to outvote a single delayed packet and short enough to follow a change. */
        const val WINDOW = 5

        /**
         * The longest round trip a sample may report and still count. Three seconds is far past
         * any mobile network on which watching together works at all, and far short of the five
         * between pings — so a ping answered after a freeze, whose trip is the freeze itself,
         * cannot pass for a slow packet. The same number as iOS's `TogetherTiming.maxRttMs`.
         */
        const val MAX_RTT_MS = 3_000L
    }
}
