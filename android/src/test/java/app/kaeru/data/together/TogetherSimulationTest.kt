package app.kaeru.data.together

import app.kaeru.domain.together.FakePlaybackPort
import app.kaeru.domain.together.FakeTransport
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.SyncPolicy
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.VirtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * An evening in a few milliseconds: this phone as the guest, an iPhone as the host, and everything
 * that pulls two pictures apart — clocks 700 ms apart, a relay with jitter, and HLS stalls of three
 * seconds on either side. What the owner saw across the room was «сходится с разницей в секунду»
 * and a seek every few minutes; this is where that has to show up before a build does.
 *
 * The host is modelled, not run: what the iOS `TogetherManager` puts on the wire and does about
 * what it hears — a report a second and a ping every five, an answer to every greeting, a hold for
 * a guest that is loading, a stall said the moment it starts — and nothing else, since the host is
 * the reference and never corrects itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TogetherSimulationTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val clock = VirtualClock { dispatcher.scheduler.currentTime }
    private val transport = FakeTransport()
    private val port = FakePlaybackPort()
    private lateinit var session: TogetherSession

    @Before
    fun setUp() {
        port.showing(animeId = 100, episode = 4, translationId = 11, positionMs = 0, playing = true)
        session = TogetherSession(
            transports = { transport },
            hosting = { HostChannel(transport, LanEndpoint("192.168.1.42", 41_234)) },
            port = port,
            clock = clock,
            scope = scope,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    /** A frame on its way through the relay: when it lands, and what it says. */
    private class Bound(val at: Long, val message: TogetherMessage)

    @Test
    fun `an evening of skewed clocks and stalls settles within the band and without seeks`() = runTest(dispatcher) {
        try {
            evening()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.evening() {
        val skew = 700L
        val step = 100L
        val end = 240_000L
        val seekStallMs = 800.0

        // The host, as far as the wire can tell.
        var hostPosition = 60_000.0
        var hostPlaying = true
        var hostStalledFor = 0.0
        var held = false
        var guestLoading = false
        var hostSeq = 1L
        // Far enough back that the first beat is due at once, and far enough from the edge that
        // subtracting it from a clock does not wrap.
        var lastReportAt = -1_000_000L
        var lastPingAt = -1_000_000L
        var reportedBuffering = false
        val toGuest = mutableListOf<Bound>()
        val toHost = mutableListOf<Bound>()
        var trips = 0
        var pongsPosted = 0
        var lastToGuest = 0L
        var lastToHost = 0L
        // Sixty to a hundred and fifty milliseconds each way, never the same twice running — but
        // never overtaking either: a socket delivers in order, and the replay guard counts on it.
        fun latency(): Long { trips++; return 60L + (trips * 37) % 90 }
        fun hostNow() = clock.millis() + skew
        fun post(make: (Long) -> TogetherMessage) {
            lastToGuest = maxOf(lastToGuest, clock.millis() + latency())
            toGuest += Bound(lastToGuest, make(hostSeq++))
        }
        fun hostReport() {
            reportedBuffering = hostStalledFor > 0
            post { seq -> TogetherMessage.State(hostPosition.toLong(), hostPlaying, hostStalledFor > 0, hostNow(), seq) }
        }

        // This side's picture, moved by hand: the fake port does not advance with the clock.
        var guestPosition = 0.0
        var guestStalledFor = 0.0
        var seeksSeen = 0
        fun guestRate() = port.rates.lastOrNull() ?: SyncPolicy.NORMAL

        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(TogetherMessage.Hello("Хозяин", 100, 4, 11, hostPosition.toLong(), true, hostSeq++))
        runCurrent()
        joining.join()
        runCurrent()
        assertTrue(session.state.value is SessionState.Live)
        // Joining is itself a jump to where the friend is, and on HLS a jump is a stall.
        guestPosition = port.state.value.positionMs.toDouble()
        guestStalledFor = seekStallMs
        port.buffering(true)
        runCurrent()
        seeksSeen = port.seeks.size
        var handled = transport.sent.size

        val guestStalls = setOf(80_000L, 150_000L)
        val hostStalls = setOf(40_000L, 120_000L, 200_000L)
        var seeksAtSteadyState = 0
        val samples = mutableListOf<Pair<Long, Long>>()

        while (clock.millis() < end) {
            advanceTimeBy(step)
            runCurrent()
            val now = clock.millis()

            // The two pictures move.
            if (guestStalledFor > 0) guestStalledFor = maxOf(0.0, guestStalledFor - step)
            else if (port.state.value.playing) guestPosition += step * guestRate()
            if (hostStalledFor > 0) hostStalledFor = maxOf(0.0, hostStalledFor - step)
            else if (hostPlaying) hostPosition += step
            if (now in guestStalls) guestStalledFor = 3_000.0
            if (now in hostStalls) hostStalledFor = 3_000.0
            // A seek the session asked for lands, and costs the stall HLS charges for one.
            if (port.seeks.size > seeksSeen) {
                seeksSeen = port.seeks.size
                guestPosition = port.seeks.last().toDouble()
                guestStalledFor = seekStallMs
            }
            port.moveTo(guestPosition.toLong())
            port.buffering(guestStalledFor > 0)
            runCurrent()

            // What reached this side.
            val arriving = toGuest.filter { it.at <= now }.sortedBy { it.at }
            toGuest.removeAll { it.at <= now }
            arriving.forEach { transport.deliver(it.message) }
            runCurrent()

            // What this side said, on its way to the host.
            transport.sent.drop(handled).forEach {
                lastToHost = maxOf(lastToHost, now + latency())
                toHost += Bound(lastToHost, it)
            }
            handled = transport.sent.size
            val landed = toHost.filter { it.at <= now }.sortedBy { it.at }
            toHost.removeAll { it.at <= now }
            for (bound in landed) {
                when (val message = bound.message) {
                    is TogetherMessage.Ping -> {
                        val at = hostNow()
                        pongsPosted++
                        post { seq -> TogetherMessage.Pong(message.sentAt, at, at, seq) }
                    }
                    // The iOS host answers every greeting with one, and a ping.
                    is TogetherMessage.Hello -> {
                        post { seq -> TogetherMessage.Hello("Хозяин", 100, 4, 11, hostPosition.toLong(), hostPlaying, seq) }
                        post { seq -> TogetherMessage.Ping(hostNow(), seq) }
                    }
                    is TogetherMessage.State -> {
                        // `peerIsLoading`: a guest that means to play and is loading is waited
                        // for; the first report to say otherwise starts the picture again.
                        val loading = message.buffering && message.playing
                        if (loading != guestLoading) {
                            guestLoading = loading
                            if (loading) {
                                if (hostPlaying) { held = true; hostPlaying = false }
                            } else if (held) {
                                held = false; hostPlaying = true
                            }
                        }
                    }
                    else -> Unit
                }
            }

            // The host's own beats: a report a second, a stall the moment it changes, a ping every five.
            if (hostNow() - lastReportAt >= 1_000) { lastReportAt = hostNow(); hostReport() }
            else if ((hostStalledFor > 0) != reportedBuffering) hostReport()
            if (hostNow() - lastPingAt >= 5_000) {
                lastPingAt = hostNow()
                post { seq -> TogetherMessage.Ping(hostNow(), seq) }
            }

            if (now == 60_000L) seeksAtSteadyState = port.seeks.size
            // Sampled between stalls, once both pictures are moving again.
            if (now > 60_000 && now % 10_000 == 0L && guestStalledFor == 0.0 && hostStalledFor == 0.0 &&
                port.state.value.playing && hostPlaying
            ) {
                samples += now to (guestPosition - hostPosition).toLong()
            }
        }

        val live = session.state.value as SessionState.Live
        val settled = samples.joinToString { "${it.first / 1000}s: ${it.second}ms" } +
            " | offset=${live.offsetMs} drift=${live.driftMs} rates=${port.rates.takeLast(6)} seeks=${port.seeks}" +
            " pings=${transport.sent.count { it is TogetherMessage.Ping }} states=${transport.sent.count { it is TogetherMessage.State }}" +
            " pongs=$pongsPosted heldNow=$held guestLoading=$guestLoading hostPlaying=$hostPlaying playing=${port.state.value.playing}"
        assertTrue("the evening should have had a quiet minute or two in it", samples.size >= 12)
        samples.forEach { (_, drift) ->
            assertTrue(
                "the two pictures should settle inside the band the policy leaves alone; drift was $settled",
                abs(drift) < SyncPolicy.IGNORE_MS,
            )
        }
        assertTrue(
            "at most one seek in three minutes of stalls; seeks were ${port.seeks}",
            port.seeks.size - seeksAtSteadyState <= 1,
        )
        assertEquals("the clocks should be read as 700 ms apart", skew.toDouble(), live.offsetMs.toDouble(), 60.0)
    }
}
