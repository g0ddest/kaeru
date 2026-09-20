package app.kaeru.data.together

import app.kaeru.domain.together.FakePlaybackPort
import app.kaeru.domain.together.FakeTransport
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.LocalAction
import app.kaeru.domain.together.LostReason
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.PeerHello
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.SyncPolicy
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.VirtualClock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason

/**
 * Two phones on one episode, with the friend played by a channel the test writes to.
 *
 * Everything with a timer in it — the ping, the state report, the sync pass, the two half-minute
 * waits — is driven by virtual time, so a session that would take a minute of somebody's evening
 * takes a millisecond here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TogetherSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val clock = VirtualClock { dispatcher.scheduler.currentTime }

    private val transport = FakeTransport()
    private val port = FakePlaybackPort()
    private lateinit var session: TogetherSession

    @Before
    fun setUp() {
        port.showing(animeId = 100, episode = 4, translationId = 11, positionMs = 60_000, playing = true)
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

    /**
     * One test, with the session's four timers switched off at the end of it.
     *
     * A session keeps a ping, a report and a sync pass on a delay for as long as it is running,
     * and `runTest` drains what is left of the scheduler once the body returns — which, against
     * three loops that always have another tick scheduled, never finishes.
     */
    private fun sessionTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            scope.cancel()
        }
    }

    private fun peerHello(
        name: String = "Аня",
        episode: Int = 4,
        translationId: Int? = 11,
        positionMs: Long = 60_000,
        playing: Boolean = true,
        seq: Long = 1,
    ) = TogetherMessage.Hello(name, 100, episode, translationId, positionMs, playing, seq)

    /**
     * Hands the session a report from the friend and lets the next sync pass act on it.
     *
     * Delivered a millisecond before the pass on purpose: a report is extrapolated forward to the
     * moment it is judged, and a fake player's picture does not advance with the clock, so a
     * report delivered two seconds early would look like a friend who had raced ahead.
     */
    private fun TestScope.friendIsAt(
        positionMs: Long,
        playing: Boolean = true,
        buffering: Boolean = false,
        seq: Long = 20,
    ) {
        advanceTimeBy(TogetherSession.SYNC_INTERVAL_MS - 1)
        runCurrent()
        transport.deliver(
            TogetherMessage.State(positionMs, playing, buffering, clock.millis(), seq),
        )
        runCurrent()
        advanceTimeBy(2)
        runCurrent()
    }

    /**
     * The same room, seen from the side that followed the link.
     *
     * The side that made the room is the reference and never corrects itself, so everything
     * about closing a gap — a nudge in speed, a jump, a jump with a sentence next to it — is
     * something only the guest does, and is tested from the guest's seat.
     */
    private suspend fun TestScope.liveAsGuest(): RoomLink {
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello())
        runCurrent()
        joining.join()
        runCurrent()
        assertTrue("гость на той же серии сразу в эфире", session.state.value is SessionState.Live)
        // Joining is itself a jump to where the friend is. What the tests below count starts after.
        port.seeks.clear()
        port.rates.clear()
        return link
    }

    /** A room with a friend already in it, seen from the side that made the link. */
    private suspend fun TestScope.live(): RoomLink {
        val link = session.host("Костя")
        runCurrent()
        transport.deliver(peerHello())
        runCurrent()
        return link
    }

    // ---- opening a room ----

    @Test
    fun `hosting opens a room, puts this phone's address in the link and waits`() = sessionTest {
        val link = session.host("Костя")
        runCurrent()

        assertEquals(LanEndpoint("192.168.1.42", 41_234), link.lan)
        assertEquals(true, transport.connectedAsHost)
        assertEquals(SessionState.Hosting(link, waiting = true), session.state.value)
    }

    @Test
    fun `a friend arriving is answered with a hello, announced, and the wait is over`() = sessionTest {
        session.host("Костя")
        runCurrent()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(peerHello(name = "Аня"))
        runCurrent()

        val hello = transport.sentOf<TogetherMessage.Hello>().single()
        assertEquals("Костя", hello.name)
        assertEquals(4, hello.episode)
        assertEquals(60_000L, hello.positionMs)
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
        assertTrue(TogetherEvent.Notice(NoticeKind.JOINED, "Аня") in seen)
        watching.cancel()
    }

    @Test
    fun `a room nobody has walked into yet stays open, however long it takes`() = sessionTest {
        val link = session.host("Костя")
        runCurrent()

        advanceTimeBy(TogetherSession.WAIT_TIMEOUT_MS * 4)
        runCurrent()
        assertEquals(SessionState.Hosting(link, waiting = true), session.state.value)

        transport.deliver(peerHello(name = "Аня"))
        runCurrent()

        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    // ---- following a link ----

    @Test
    fun `joining says hello, waits for one back and returns with something to draw`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), LanEndpoint("192.168.1.42", 41_234))
        val joining = launch { session.join(link, "Костя") }
        runCurrent()

        assertEquals(false, transport.connectedAsHost)
        assertEquals("Костя", transport.sentOf<TogetherMessage.Hello>().single().name)
        assertEquals(SessionState.Joining(link, hello = null), session.state.value)

        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 22, positionMs = 930_000))
        runCurrent()

        joining.join()

        assertEquals(
            SessionState.Joining(
                link,
                PeerHello("Аня", 100, 7, 22, 930_000, playing = true),
            ),
            session.state.value,
        )
        // Nothing has started a video behind a question the viewer has not answered.
        assertTrue(port.seeks.isEmpty())
    }

    @Test
    fun `the joiner's first outbound message is its hello`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val joining = launch { session.join(RoomLink("room", ByteArray(16), null), "Костя") }
        runCurrent()

        assertTrue(transport.sent.first() is TogetherMessage.Hello)
        joining.cancel()
    }

    @Test
    fun `it goes live when the viewer's own screen opens the episode`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 22, positionMs = 930_000))
        runCurrent()
        joining.join()
        assertTrue(session.state.value is SessionState.Joining)

        port.showing(animeId = 100, episode = 7, translationId = 22, positionMs = 0)
        runCurrent()

        assertEquals(listOf(930_000L), port.seeks)
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `a host who moves on while the guest is reading is followed, not lost`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
        runCurrent()
        joining.join()

        // Their autoplay ran into the next episode while the invitation sat on screen.
        transport.deliver(TogetherMessage.Episode(episode = 8, translationId = 11, seq = 5))
        runCurrent()
        // Nothing has been done to a player the viewer has not opened yet.
        assertTrue(port.opened.isEmpty())
        assertTrue(session.state.value is SessionState.Joining)

        // The screen opens the episode it was invited to, which is no longer where they are.
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        runCurrent()

        assertEquals(8, port.opened.single().episode)
        assertEquals(8, port.state.value.episode)
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `a guest joining a friend who is paused lands paused`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 930_000, playing = false))
        runCurrent()
        joining.join()

        // The player is starting the episode: it plays the moment it is prepared, so a pause
        // sent before that would be undone by the start itself.
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0, ready = false)
        runCurrent()
        assertEquals(0, port.pauses)

        port.ready()
        runCurrent()

        assertEquals(listOf(930_000L), port.seeks)
        assertEquals(1, port.pauses)
        assertFalse(port.state.value.playing)
        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `joining seeks only once the episode is ready, not when it is merely asked for`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
        runCurrent()
        joining.join()

        // The player names the episode the moment it is told to open it, seconds before the
        // stream is resolved and the manifest read. A seek made then lands on nothing and is
        // written over by the position the episode is then prepared at.
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0, ready = false)
        runCurrent()

        assertTrue(port.seeks.isEmpty())
        assertTrue(session.state.value is SessionState.Joining)

        port.ready()
        runCurrent()

        assertEquals(listOf(930_000L), port.seeks)
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `following a friend who moved on waits for the new episode to be ready too`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        port.opensReady = false
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
        runCurrent()
        joining.join()
        transport.deliver(TogetherMessage.Episode(episode = 8, translationId = 11, seq = 5))
        runCurrent()

        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        runCurrent()

        // Opening the episode they moved to is a second resolve, with the same window in it.
        assertEquals(8, port.opened.single().episode)
        assertTrue(port.seeks.isEmpty())
        assertTrue(session.state.value is SessionState.Joining)

        port.ready()
        runCurrent()

        assertEquals(listOf(930_000L), port.seeks)
        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `a friend who paused while the invitation was being read is joined paused`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 930_000, playing = true))
        runCurrent()
        joining.join()
        // Their pause itself cannot reach a join screen; their reports say it all the same.
        transport.deliver(
            TogetherMessage.State(940_000, playing = false, buffering = false, sentAt = clock.millis(), seq = 9),
        )
        runCurrent()

        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        runCurrent()

        assertEquals(listOf(940_000L), port.seeks)
        assertEquals(1, port.pauses)
        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `a resolve that fails still goes live, so the player's own retry keeps the session`() =
        sessionTest {
            port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
            val link = RoomLink("room", ByteArray(16), null)
            val joining = launch { session.join(link, "Костя") }
            runCurrent()
            transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
            runCurrent()
            joining.join()

            // Kodik is down, or the phone is: the player names the episode and then says it
            // cannot play it. Nothing here will ever be ready, and a wait for it never ends.
            port.showing(
                animeId = 100, episode = 7, translationId = 11,
                positionMs = 0, playing = false, ready = false, failed = true,
            )
            runCurrent()

            // Live on a picture that is not playing: there is nothing to seek and nothing to
            // pause, and «Повторить» on the player's own error screen is still in the session.
            assertTrue(port.seeks.isEmpty())
            assertEquals(0, port.pauses)
            assertTrue(session.state.value is SessionState.Live)

            // The retry works, and the friend's next report is what puts this side beside them.
            port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
            friendIsAt(960_000)

            assertEquals(listOf(960_000L), port.seeks)
        }

    @Test
    fun `a friend who moved on while the invitation was read names the episode to open`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
        runCurrent()
        joining.join()

        transport.deliver(TogetherMessage.Episode(episode = 8, translationId = 11, seq = 5))
        runCurrent()

        // The screen opens what the session is about to follow anyway, rather than the episode
        // named in the hello — and at the start of it, because nothing said otherwise yet and
        // the hello's position is a position in the episode before.
        assertEquals(8, (session.state.value as SessionState.Joining).pendingEpisode)
        assertEquals(0L, session.peerPositionNow())
    }

    @Test
    fun `a friend who reported from the episode they moved to is joined at their place in it`() =
        sessionTest {
            port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
            val link = RoomLink("room", ByteArray(16), null)
            val joining = launch { session.join(link, "Костя") }
            runCurrent()
            transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
            runCurrent()
            joining.join()
            transport.deliver(TogetherMessage.Episode(episode = 8, translationId = 11, seq = 5))
            runCurrent()
            transport.deliver(
                TogetherMessage.State(30_000, playing = true, buffering = false, sentAt = clock.millis(), seq = 9),
            )
            runCurrent()

            assertEquals(8, (session.state.value as SessionState.Joining).pendingEpisode)
            assertEquals(30_000L, session.peerPositionNow())

            // And having opened that episode there, the session has nothing left to open.
            port.showing(animeId = 100, episode = 8, translationId = 11, positionMs = 30_000)
            runCurrent()

            assertTrue("nothing was opened a second time", port.opened.isEmpty())
            assertEquals(listOf(30_000L), port.seeks)
            assertTrue(session.state.value is SessionState.Live)
        }

    @Test
    fun `a guest acts on the friend's first report at once, not on the next tick`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 930_000))
        runCurrent()
        joining.join()
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        runCurrent()
        assertEquals(listOf(930_000L), port.seeks)
        assertTrue(session.state.value is SessionState.Live)

        // The invitation was read for twenty seconds, and no tick has come round yet.
        transport.deliver(
            TogetherMessage.State(950_000, playing = true, buffering = false, sentAt = clock.millis(), seq = 9),
        )
        runCurrent()

        assertEquals(listOf(930_000L, 950_000L), port.seeks)
    }

    @Test
    fun `the first correction waits for the picture to start rather than for the next tick`() =
        sessionTest {
            port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
            val link = RoomLink("room", ByteArray(16), null)
            val joining = launch { session.join(link, "Костя") }
            runCurrent()
            transport.deliver(peerHello(name = "Аня", episode = 7, translationId = 11, positionMs = 930_000))
            runCurrent()
            joining.join()

            // Ready, and then buffering the first frames — which is where a guest on HLS spends
            // the seconds after the seek. The policy will not judge a picture that is not moving.
            port.showing(
                animeId = 100, episode = 7, translationId = 11,
                positionMs = 0, playing = false, buffering = true,
            )
            runCurrent()
            assertEquals(listOf(930_000L), port.seeks)
            assertTrue(session.state.value is SessionState.Live)

            transport.deliver(
                TogetherMessage.State(950_000, playing = true, buffering = false, sentAt = clock.millis(), seq = 9),
            )
            runCurrent()
            assertEquals("nothing to judge while the picture is stopped", listOf(930_000L), port.seeks)

            // The first frames arrive, twenty seconds behind, and the gap is closed there and
            // then rather than on whichever tick comes next.
            port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 930_000)
            runCurrent()

            assertEquals(listOf(930_000L, 950_000L), port.seeks)
        }

    @Test
    fun `where the friend is now is their last report carried forward, or the hello until then`() =
        sessionTest {
            port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
            val link = RoomLink("room", ByteArray(16), null)
            val joining = launch { session.join(link, "Костя") }
            runCurrent()
            assertNull(session.peerPositionNow())

            transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 600_000, playing = true))
            runCurrent()
            joining.join()
            // Nothing reported yet: the hello, carried forward by the time spent reading it.
            advanceTimeBy(15_000)
            assertEquals(615_000L, session.peerPositionNow())

            transport.deliver(
                TogetherMessage.State(700_000, playing = true, buffering = false, sentAt = clock.millis(), seq = 9),
            )
            runCurrent()
            advanceTimeBy(3_000)

            assertEquals(703_000L, session.peerPositionNow())
        }

    @Test
    fun `a friend who said hello paused is where they said they were, however long ago`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 600_000, playing = false))
        runCurrent()
        joining.join()

        advanceTimeBy(15_000)

        assertEquals(600_000L, session.peerPositionNow())
    }

    @Test
    fun `an episode already on screen goes live at once`() = sessionTest {
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 930_000))
        runCurrent()
        joining.join()

        assertEquals(listOf(930_000L), port.seeks)
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `joining lands where the friend is now, not where they were when they said hello`() =
        sessionTest {
            port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
            val link = RoomLink("room", ByteArray(16), null)
            val joining = launch { session.join(link, "Костя") }
            runCurrent()
            transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 600_000))
            runCurrent()
            // They carried on watching while the invitation sat on screen.
            transport.deliver(
                TogetherMessage.State(660_000, playing = true, buffering = false, sentAt = clock.millis(), seq = 9),
            )
            runCurrent()

            port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
            runCurrent()

            assertEquals(listOf(660_000L), port.seeks)
        }

    @Test
    fun `joining lands where the friend is, allowing for the difference in the clocks`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        // One round trip, on a channel where the friend's clock reads four seconds ahead.
        val ping = transport.sentOf<TogetherMessage.Ping>().single()
        transport.deliver(
            TogetherMessage.Pong(
                pingSentAt = ping.sentAt,
                receivedAt = ping.sentAt + 4_000,
                sentAt = ping.sentAt + 4_000,
                seq = 1,
            ),
        )
        runCurrent()

        transport.deliver(peerHello(positionMs = 600_000, seq = 2))
        runCurrent()
        port.showing(animeId = 100, episode = 4, translationId = 11, positionMs = 0)
        runCurrent()
        joining.join()

        assertEquals(listOf(604_000L), port.seeks)
        assertEquals(SessionState.Live("Аня", 4_000, 0), session.state.value)
    }

    @Test
    fun `the host's first outbound message is its hello`() = sessionTest {
        session.host("Костя")
        runCurrent()
        assertTrue(transport.sent.isEmpty())

        transport.deliver(peerHello(name = "Аня"))
        runCurrent()

        assertTrue(transport.sent.first() is TogetherMessage.Hello)
    }

    @Test
    fun `nobody says hello back for half a minute and the wait is called off`() = sessionTest {
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        advanceTimeBy(TogetherSession.WAIT_TIMEOUT_MS + 1)
        runCurrent()
        joining.join()

        assertEquals(SessionState.Lost(LostReason.WAIT_TIMEOUT), session.state.value)
        assertTrue(port.seeks.isEmpty())
    }

    @Test
    fun `a build with no relay says so rather than showing a network error`() = sessionTest {
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(RelayNotConfigured())
        transport.finish()
        runCurrent()
        joining.join()

        assertEquals(SessionState.Lost(LostReason.NOT_CONFIGURED), session.state.value)
    }

    @Test
    fun `a write that fails on the way out does not rewrite why the room closed`() = sessionTest {
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()

        // The relay says the room is full and stops taking writes, in that order.
        transport.deliver(TogetherFailed(TogetherFailureReason.ROOM_FULL))
        transport.sendFailure = TogetherFailed(TogetherFailureReason.UNREACHABLE)
        runCurrent()
        session.sendChat("ау")
        transport.finish()
        runCurrent()
        joining.join()

        assertEquals(SessionState.Lost(LostReason.ROOM_FULL), session.state.value)
    }

    @Test
    fun `a room that already has two people in it says which of them this is not`() = sessionTest {
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(TogetherFailed(TogetherFailureReason.ROOM_FULL))
        transport.finish()
        runCurrent()
        joining.join()

        assertEquals(SessionState.Lost(LostReason.ROOM_FULL), session.state.value)
    }

    @Test
    fun `a voice this device cannot get is mentioned rather than hidden`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val link = RoomLink("room", ByteArray(16), null)
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 4, translationId = 22))
        runCurrent()
        // This device only has the other studio, and played it.
        port.showing(animeId = 100, episode = 4, translationId = 11, positionMs = 0)
        runCurrent()
        joining.join()

        assertTrue(TogetherEvent.Notice(NoticeKind.OTHER_VOICE, "Аня") in seen)
        watching.cancel()
    }

    // ---- what the friend did ----

    @Test
    fun `a friend's pause pauses this phone and is said out loud`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Pause(positionMs = 61_000, seq = 2))
        runCurrent()

        assertEquals(1, port.pauses)
        assertTrue(TogetherEvent.Notice(NoticeKind.PAUSED, "Аня", positionMs = 61_000) in seen)
        watching.cancel()
    }

    @Test
    fun `a friend's play plays this phone and is said out loud`() = sessionTest {
        live()
        port.pause()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Play(positionMs = 61_000, seq = 2))
        runCurrent()

        assertEquals(1, port.plays)
        assertTrue(TogetherEvent.Notice(NoticeKind.PLAYED, "Аня", positionMs = 61_000) in seen)
        watching.cancel()
    }

    @Test
    fun `a friend's seek moves this phone and is said out loud`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 2))
        runCurrent()

        assertEquals(listOf(300_000L), port.seeks)
        assertTrue(TogetherEvent.Notice(NoticeKind.SEEKED, "Аня", positionMs = 300_000) in seen)
        watching.cancel()
    }

    @Test
    fun `nothing a friend did is sent back to them`() = sessionTest {
        live()
        transport.sent.clear()

        transport.deliver(TogetherMessage.Pause(positionMs = 61_000, seq = 2))
        transport.deliver(TogetherMessage.Play(positionMs = 61_000, seq = 3))
        transport.deliver(TogetherMessage.Seek(positionMs = 90_000, seq = 4))
        runCurrent()

        assertTrue(transport.sentOf<TogetherMessage.Pause>().isEmpty())
        assertTrue(transport.sentOf<TogetherMessage.Play>().isEmpty())
        assertTrue(transport.sentOf<TogetherMessage.Seek>().isEmpty())
    }

    @Test
    fun `the last action wins and a straggler that lost the race is ignored`() = sessionTest {
        live()

        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 9))
        runCurrent()
        transport.deliver(TogetherMessage.Seek(positionMs = 120_000, seq = 8))
        runCurrent()

        assertEquals(listOf(300_000L), port.seeks)
    }

    @Test
    fun `a frame handed over twice is acted on once`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()
        val chat = TogetherMessage.Chat("Дальше!", seq = 5)

        transport.deliver(chat)
        runCurrent()
        // The same encrypted frame, played back by a relay nobody has to trust.
        transport.deliver(chat)
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 4))
        runCurrent()

        assertEquals(1, seen.count { it is TogetherEvent.ChatItem })
        assertTrue(port.seeks.isEmpty())
        watching.cancel()
    }

    @Test
    fun `an episode a friend opened opens here too`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Episode(episode = 5, translationId = 11, seq = 2))
        runCurrent()

        assertEquals(
            FakePlaybackPort.Opened(animeId = 100, episode = 5, translationId = 11, positionMs = 0),
            port.opened.single(),
        )
        assertTrue(TogetherEvent.Notice(NoticeKind.EPISODE, "Аня", episode = 5) in seen)
        watching.cancel()
    }

    @Test
    fun `an episode already on screen is not restarted to be told about`() = sessionTest {
        live()

        transport.deliver(TogetherMessage.Episode(episode = 4, translationId = 11, seq = 2))
        runCurrent()

        assertTrue(port.opened.isEmpty())
    }

    @Test
    fun `an episode in a voice this device lacks is opened anyway and mentioned`() = sessionTest {
        live()
        port.fallbackTranslationId = 11
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Episode(episode = 5, translationId = 22, seq = 2))
        runCurrent()

        assertEquals(5, port.opened.single().episode)
        assertTrue(TogetherEvent.Notice(NoticeKind.OTHER_VOICE, "Аня") in seen)
        watching.cancel()
    }

    @Test
    fun `a friend who is buffering does not stop this picture`() = sessionTest {
        live()

        repeat(6) { friendIsAt(60_000, playing = false, buffering = true, seq = 10L + it) }

        assertEquals(0, port.pauses)
        assertTrue(port.seeks.isEmpty())
        assertTrue(port.rates.isEmpty())
    }

    /**
     * A friend who means to be playing but is still filling their buffer.
     *
     * Both sides always reported it and neither ever read it, so one phone stalling on a segment
     * left the other playing on — and once the gap passed ten seconds the rule said seek, which
     * dragged the stalled phone forward onto a segment it did not have. «Перемотал на» every few
     * seconds for as long as the network was slow.
     */
    @Test
    fun `this picture waits for a friend who is still loading`() = sessionTest {
        live()

        friendIsAt(60_000, playing = true, buffering = true, seq = 20L)

        assertEquals(1, port.pauses)
        assertTrue("никаких перемоток, пока друг подгружает", port.seeks.isEmpty())

        friendIsAt(60_500, playing = true, buffering = false, seq = 21L)

        assertEquals(1, port.plays)
    }

    /** The side that made the room is what the picture is measured against; it never moves. */
    @Test
    fun `the host never corrects itself`() = sessionTest {
        live()
        friendIsAt(59_000)
        friendIsAt(75_000, seq = 21)
        assertTrue(port.rates.isEmpty())
        assertTrue(port.seeks.isEmpty())
    }

    // ---- what this viewer did ----

    @Test
    fun `what this viewer does is sent once`() = sessionTest {
        live()
        transport.sent.clear()

        port.did(LocalAction.Pause(62_000))
        runCurrent()
        port.did(LocalAction.Play(62_000))
        runCurrent()
        port.did(LocalAction.Seek(180_000))
        runCurrent()
        port.did(LocalAction.Episode(100, 5, 11))
        runCurrent()

        assertEquals(62_000L, transport.sentOf<TogetherMessage.Pause>().single().positionMs)
        assertEquals(62_000L, transport.sentOf<TogetherMessage.Play>().single().positionMs)
        assertEquals(180_000L, transport.sentOf<TogetherMessage.Seek>().single().positionMs)
        assertEquals(5, transport.sentOf<TogetherMessage.Episode>().single().episode)
    }

    @Test
    fun `this viewer acting after a friend still wins, because their count went higher`() = sessionTest {
        live()
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 40))
        runCurrent()

        port.did(LocalAction.Seek(120_000))
        runCurrent()
        // And the friend's next word, from a count that has not caught up, does not undo it.
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 41))
        runCurrent()

        assertTrue(transport.sentOf<TogetherMessage.Seek>().single().seq > 40)
        assertEquals(listOf(300_000L), port.seeks)
    }

    // ---- keeping time ----

    @Test
    fun `where this side is goes out once a second`() = sessionTest {
        live()
        transport.sent.clear()

        advanceTimeBy(TogetherSession.STATE_INTERVAL_MS * 3 + 1)
        runCurrent()

        assertEquals(3, transport.sentOf<TogetherMessage.State>().size)
        assertEquals(60_000L, transport.sentOf<TogetherMessage.State>().first().positionMs)
    }

    @Test
    fun `a ping goes out every five seconds and the answer sets the offset`() = sessionTest {
        live()
        transport.sent.clear()

        advanceTimeBy(TogetherSession.PING_INTERVAL_MS * 2 + 1)
        runCurrent()
        val pings = transport.sentOf<TogetherMessage.Ping>()
        assertEquals(2, pings.size)

        val at = clock.millis()
        transport.deliver(
            TogetherMessage.Pong(
                pingSentAt = pings.last().sentAt,
                receivedAt = at + 2_000,
                sentAt = at + 2_000,
                seq = 50,
            ),
        )
        runCurrent()

        assertEquals(2_000L, (session.state.value as SessionState.Live).offsetMs)
    }

    @Test
    fun `a friend's ping is answered with the three times it takes to work the offset out`() = sessionTest {
        live()
        transport.sent.clear()

        transport.deliver(TogetherMessage.Ping(sentAt = 777, seq = 30))
        runCurrent()

        val pong = transport.sentOf<TogetherMessage.Pong>().single()
        assertEquals(777L, pong.pingSentAt)
        assertEquals(clock.millis(), pong.receivedAt)
    }

    @Test
    fun `a gap of a couple of seconds is closed by playing a little slower`() = sessionTest {
        liveAsGuest()
        // The friend is a second behind: this side is at a minute, theirs at fifty-nine seconds.
        friendIsAt(59_000)

        assertEquals(SyncPolicy.SLOW, port.rates.first(), 0.0001f)
        assertTrue(port.seeks.isEmpty())
    }

    @Test
    fun `a gap too big to play out of is jumped, and a huge one is jumped and mentioned`() = sessionTest {
        liveAsGuest()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        friendIsAt(20_000)

        assertTrue(port.seeks.isNotEmpty())
        assertTrue(seen.any { it is TogetherEvent.Notice && it.kind == NoticeKind.CATCHING_UP })
        watching.cancel()
    }

    @Test
    fun `the drift the screen shows is the drift the session acts on`() = sessionTest {
        live()
        // Their clock reads three seconds ahead of this one.
        val ping = transport.sentOf<TogetherMessage.Ping>().single()
        transport.deliver(
            TogetherMessage.Pong(ping.sentAt, ping.sentAt + 3_000, ping.sentAt + 3_000, seq = 2),
        )
        runCurrent()
        assertEquals(3_000L, (session.state.value as SessionState.Live).offsetMs)

        // A report a second old by this device's clock, so a second of their playback is missing
        // from the position it carries.
        advanceTimeBy(1_000)
        runCurrent()
        transport.deliver(
            TogetherMessage.State(
                positionMs = 59_200,
                playing = true,
                buffering = false,
                // Their clock, three seconds ahead, a second ago.
                sentAt = clock.millis() + 3_000 - 1_000,
                seq = 3,
            ),
        )
        runCurrent()

        // 60 000 here against 59 200 + 1 000 travelled + 3 000 − 3 000 of clock: 200 ms behind.
        assertEquals(-200L, (session.state.value as SessionState.Live).driftMs)
    }

    // ---- talking ----

    @Test
    fun `a line of chat goes out and is shown here as this viewer's own`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        session.sendChat("Стоп, что?")
        runCurrent()

        assertEquals("Стоп, что?", transport.sentOf<TogetherMessage.Chat>().single().text)
        assertTrue(seen.any { it is TogetherEvent.ChatItem && !it.fromPeer && it.text == "Стоп, что?" })
        watching.cancel()
    }

    @Test
    fun `a line longer than the protocol allows is cut rather than refused`() = sessionTest {
        live()

        session.sendChat("я".repeat(500))
        runCurrent()

        assertEquals(
            TogetherMessage.MAX_CHAT_CHARS,
            transport.sentOf<TogetherMessage.Chat>().single().text.length,
        )
    }

    @Test
    fun `a friend's line and a friend's reaction arrive as theirs`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Chat("Дальше!", seq = 2))
        transport.deliver(TogetherMessage.Reaction(ReactionKind.FIRE, seq = 3))
        runCurrent()

        assertTrue(seen.any { it is TogetherEvent.ChatItem && it.fromPeer && it.text == "Дальше!" })
        assertTrue(seen.any { it is TogetherEvent.ReactionEvent && it.fromPeer && it.kind == ReactionKind.FIRE })
        watching.cancel()
    }

    @Test
    fun `a voice clip is cut into frames that fit and put back together in order`() = sessionTest {
        live()
        val clip = ByteArray(80_000) { (it % 251).toByte() }

        session.sendVoice(clip, durationMs = 7_000)
        runCurrent()

        val slices = transport.sentOf<TogetherMessage.Voice>()
        assertEquals(3, slices.size)
        assertTrue(slices.all { it.bytes.size <= TogetherMessage.MAX_VOICE_CHUNK_BYTES })
        assertEquals(listOf(0, 1, 2), slices.map { it.chunk })
        assertTrue(slices.all { it.total == 3 })
        assertTrue(
            clip.contentEquals(slices.fold(ByteArray(0)) { all, slice -> all + slice.bytes }),
        )
    }

    @Test
    fun `a clip nobody could have recorded is dropped rather than sent`() = sessionTest {
        live()

        session.sendVoice(ByteArray(TogetherSession.MAX_VOICE_BYTES + 1), durationMs = 7_000)
        runCurrent()

        assertTrue(transport.sentOf<TogetherMessage.Voice>().isEmpty())
    }

    @Test
    fun `a friend's clip is shown only once every piece of it has arrived`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()
        val clip = ByteArray(70_000) { (it % 97).toByte() }
        val slices = clip.toList().chunked(TogetherMessage.MAX_VOICE_CHUNK_BYTES)

        slices.forEachIndexed { index, bytes ->
            transport.deliver(
                TogetherMessage.Voice(index, slices.size, bytes.toByteArray(), durationMs = 6_000, seq = 10L + index),
            )
            runCurrent()
            if (index < slices.lastIndex) {
                assertTrue(seen.none { it is TogetherEvent.VoiceClip })
            }
        }

        val arrived = seen.filterIsInstance<TogetherEvent.VoiceClip>().single()
        assertTrue(arrived.fromPeer)
        assertEquals(6_000, arrived.durationMs)
        assertTrue(clip.contentEquals(arrived.bytes))
        watching.cancel()
    }

    @Test
    fun `half a clip whose rest never came is thrown away`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Voice(0, 2, ByteArray(100), durationMs = 4_000, seq = 10))
        advanceTimeBy(TogetherSession.VOICE_TIMEOUT_MS + 1_000)
        runCurrent()
        transport.deliver(TogetherMessage.Voice(1, 2, ByteArray(100), durationMs = 4_000, seq = 11))
        runCurrent()

        assertTrue(seen.none { it is TogetherEvent.VoiceClip })
        watching.cancel()
    }

    // ---- endings ----

    @Test
    fun `a friend who says goodbye ends it there and then`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.Bye(seq = 9))
        runCurrent()

        assertTrue(TogetherEvent.Notice(NoticeKind.LEFT, "Аня") in seen)
        assertEquals(SessionState.Ended, session.state.value)
        assertEquals(1, transport.closes)

        // And no half-minute afterthought that says the connection was lost.
        advanceTimeBy(TogetherSession.REJOIN_WINDOW_MS * 2)
        runCurrent()
        assertEquals(SessionState.Ended, session.state.value)
        watching.cancel()
    }

    @Test
    fun `a deliberate leave never shows the connection as lost`() = sessionTest {
        live()
        val states = mutableListOf<SessionState>()
        val watching = launch { session.state.toList(states) }
        runCurrent()

        session.leave()
        runCurrent()

        // Closing the channel ends the flow the session is collecting, and the line after that
        // collect is what calls a finished channel a lost connection. Nothing the viewer chose
        // may go through that door.
        assertTrue(states.none { it is SessionState.Lost })
        assertEquals(SessionState.Ended, states.last())
        watching.cancel()
    }

    @Test
    fun `the goodbye is written and the channel closed before the collector is taken down`() =
        sessionTest {
            live()
            transport.order.clear()

            session.leave()
            runCurrent()

            // The order is the whole finding: cancelling the collector runs the transport's own
            // teardown, which cuts the socket, and a goodbye still queued would go with it.
            assertEquals(listOf("bye", "close-first", "close"), transport.order.take(3))
        }

    @Test
    fun `a friend whose socket went away is announced and given half a minute to come back`() = sessionTest {
        live()
        val seen = mutableListOf<TogetherEvent>()
        val watching = launch { session.events.toList(seen) }
        runCurrent()

        transport.deliver(TogetherMessage.PeerLeft())
        runCurrent()

        assertTrue(TogetherEvent.Notice(NoticeKind.LEFT, "Аня") in seen)
        assertTrue(session.state.value is SessionState.Live)

        advanceTimeBy(TogetherSession.REJOIN_WINDOW_MS + 1)
        runCurrent()

        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)
        watching.cancel()
    }

    @Test
    fun `a friend who comes back inside the window picks up where they were`() = sessionTest {
        live()

        transport.deliver(TogetherMessage.PeerLeft())
        advanceTimeBy(TogetherSession.REJOIN_WINDOW_MS / 2)
        runCurrent()
        transport.deliver(peerHello(name = "Аня", seq = 20))
        advanceTimeBy(TogetherSession.REJOIN_WINDOW_MS + 1)
        runCurrent()

        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `a friend who comes back counting from one again is still let in`() = sessionTest {
        live()
        // A minute of state reports and pings, so this side's high-water mark is well past what a
        // phone that has just reconnected will be sending.
        repeat(30) { transport.deliver(TogetherMessage.Ping(sentAt = clock.millis(), seq = 2L + it)) }
        runCurrent()

        transport.deliver(TogetherMessage.PeerLeft())
        runCurrent()
        // Their session started over, so their counter did too.
        transport.deliver(peerHello(name = "Аня", seq = 1))
        runCurrent()
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)

        // And what they do next is acted on rather than dropped as a replay.
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 2))
        runCurrent()

        assertEquals(listOf(300_000L), port.seeks)
        advanceTimeBy(TogetherSession.REJOIN_WINDOW_MS + 1)
        runCurrent()
        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `nothing the host does reaches a guest still on its join screen`() = sessionTest {
        port.showing(animeId = 500, episode = 2, translationId = 11, positionMs = 120_000)
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7, positionMs = 930_000))
        runCurrent()
        joining.join()
        assertTrue(session.state.value is SessionState.Joining)

        transport.deliver(TogetherMessage.Pause(positionMs = 940_000, seq = 2))
        transport.deliver(TogetherMessage.Seek(positionMs = 950_000, seq = 3))
        transport.deliver(TogetherMessage.Episode(episode = 8, translationId = 22, seq = 4))
        runCurrent()

        assertEquals(0, port.pauses)
        assertTrue(port.seeks.isEmpty())
        assertTrue(port.opened.isEmpty())
        assertEquals(2, port.state.value.episode)
    }

    @Test
    fun `the window a friend comes back through is not open to anything else`() = sessionTest {
        live()
        repeat(30) { transport.deliver(TogetherMessage.Ping(sentAt = clock.millis(), seq = 2L + it)) }
        runCurrent()
        transport.deliver(TogetherMessage.PeerLeft())
        runCurrent()

        // A frame somebody captured earlier, replayed into the window.
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 4))
        transport.deliver(TogetherMessage.Chat("сказанное однажды", seq = 5))
        runCurrent()

        assertTrue(port.seeks.isEmpty())
        assertTrue(session.state.value is SessionState.Live)

        // And the hello of the friend actually walking back in is still let through.
        transport.deliver(peerHello(name = "Аня", seq = 1))
        runCurrent()
        transport.deliver(TogetherMessage.Seek(positionMs = 420_000, seq = 2))
        runCurrent()

        assertEquals(listOf(420_000L), port.seeks)
    }

    @Test
    fun `the exception that window makes is spent on the first hello through it`() = sessionTest {
        live()
        repeat(30) { transport.deliver(TogetherMessage.Ping(sentAt = clock.millis(), seq = 2L + it)) }
        runCurrent()
        transport.deliver(TogetherMessage.PeerLeft())
        runCurrent()

        // The friend walks back in and their count starts over.
        transport.deliver(peerHello(name = "Аня", seq = 1))
        transport.deliver(TogetherMessage.Seek(positionMs = 420_000, seq = 2))
        runCurrent()
        assertEquals(listOf(420_000L), port.seeks)

        // A relay handing the same hello back cannot re-open the door behind them.
        transport.deliver(peerHello(name = "Аня", seq = 1))
        transport.deliver(TogetherMessage.Seek(positionMs = 300_000, seq = 2))
        runCurrent()

        assertEquals(listOf(420_000L), port.seeks)
    }

    @Test
    fun `a channel that will not come back is the end of it`() = sessionTest {
        live()

        transport.finish()
        runCurrent()

        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)
    }

    @Test
    fun `a session that dies mid-correction gives the picture its speed back`() = sessionTest {
        liveAsGuest()
        friendIsAt(59_000)
        assertEquals(SyncPolicy.SLOW, port.rates.single(), 0.0001f)

        transport.finish()
        runCurrent()

        assertEquals(SyncPolicy.NORMAL, port.rates.last(), 0.0001f)
    }

    @Test
    fun `an episode change mid-correction does not carry the speed into it`() = sessionTest {
        liveAsGuest()
        friendIsAt(59_000)
        assertEquals(SyncPolicy.SLOW, port.rates.single(), 0.0001f)

        transport.deliver(TogetherMessage.Episode(episode = 5, translationId = 11, seq = 30))
        runCurrent()

        assertEquals(SyncPolicy.NORMAL, port.rates.last(), 0.0001f)
        assertEquals(5, port.opened.single().episode)
    }

    @Test
    fun `leaving says goodbye, closes the channel and stops there`() = sessionTest {
        live()

        session.leave()
        runCurrent()

        assertEquals(1, transport.sentOf<TogetherMessage.Bye>().size)
        assertEquals(1, transport.closes)
        assertEquals(SessionState.Ended, session.state.value)
        assertEquals(listOf(SyncPolicy.NORMAL), port.rates)
    }

    @Test
    fun `a host who stops watching the door leaves it open`() = sessionTest {
        val link = session.host("Костя")
        runCurrent()

        session.watchAlone()
        runCurrent()

        assertEquals(SessionState.Hosting(link, waiting = false), session.state.value)
        assertEquals(0, transport.closes)

        transport.deliver(peerHello(name = "Аня"))
        runCurrent()
        assertEquals(SessionState.Live("Аня", 0, 0), session.state.value)
    }

    @Test
    fun `a guest who says no is done, and the picture is untouched`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        val joining = launch { session.join(RoomLink("room", ByteArray(16), null), "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7))
        runCurrent()
        joining.join()

        session.watchAlone()
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(1, transport.closes)
        assertEquals(0, port.pauses)
        assertTrue(port.seeks.isEmpty())
    }

    @Test
    fun `watching alone after a loss leaves nothing behind`() = sessionTest {
        live()
        transport.finish()
        runCurrent()
        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)

        session.watchAlone()
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(0, port.pauses)
    }

    @Test
    fun `leaving what has already been left changes nothing`() = sessionTest {
        live()
        session.leave()
        runCurrent()
        transport.sent.clear()

        session.leave()
        runCurrent()

        assertEquals(SessionState.Ended, session.state.value)
        assertTrue(transport.sent.isEmpty())
        assertEquals(1, transport.closes)
    }

    @Test
    fun `a session that is over sends nothing more`() = sessionTest {
        live()
        session.leave()
        runCurrent()
        transport.sent.clear()

        session.sendChat("ау")
        session.sendReaction(ReactionKind.CLAP)
        advanceTimeBy(TogetherSession.STATE_INTERVAL_MS * 5)
        runCurrent()

        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a write that fails is a dropped action, not a crash`() = sessionTest {
        live()
        transport.sendFailure = TogetherFailed(TogetherFailureReason.UNREACHABLE)

        session.sendChat("ау")
        port.did(LocalAction.Seek(120_000))
        advanceTimeBy(TogetherSession.STATE_INTERVAL_MS * 2)
        runCurrent()

        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `a channel that throws rather than failing is still only a lost session`() = sessionTest {
        transport.connectFailure = IllegalStateException("a transport with a bug in it")

        session.host("Костя")
        runCurrent()

        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)
        assertEquals(1, transport.closes)
    }

    @Test
    fun `a throw where nobody is waiting for it does not take the app down`() = sessionTest {
        port.showing(animeId = null, episode = null, translationId = null, positionMs = 0, playing = false)
        port.seekFailure = IllegalStateException("the player broke")
        val link = RoomLink("room", ByteArray(16), null)
        val joining = launch { session.join(link, "Костя") }
        runCurrent()
        transport.deliver(peerHello(name = "Аня", episode = 7))
        runCurrent()
        joining.join()

        // The seek that finishes the join is the only thing holding this; it throws into a
        // coroutine with nothing awaiting it.
        port.showing(animeId = 100, episode = 7, translationId = 11, positionMs = 0)
        runCurrent()

        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)
        assertEquals(1, transport.closes)
    }

    @Test
    fun `a peer shouting through the wrong door is not waited on for ever`() = sessionTest {
        session.host("Костя")
        runCurrent()

        repeat(TogetherSession.GARBLED_LIMIT) {
            transport.deliver(TogetherFailed(TogetherFailureReason.TAMPERED))
        }
        runCurrent()

        assertEquals(SessionState.Lost(LostReason.CONNECTION), session.state.value)
    }

    @Test
    fun `a run of bad frames broken by a good one starts counting again`() = sessionTest {
        live()

        repeat(TogetherSession.GARBLED_LIMIT - 1) {
            transport.deliver(TogetherFailed(TogetherFailureReason.TAMPERED))
        }
        transport.deliver(TogetherMessage.Chat("слышно", seq = 5))
        repeat(TogetherSession.GARBLED_LIMIT - 1) {
            transport.deliver(TogetherFailed(TogetherFailureReason.TAMPERED))
        }
        runCurrent()

        assertTrue(session.state.value is SessionState.Live)
    }

    @Test
    fun `a correction the player can no longer honour is taken off`() = sessionTest {
        liveAsGuest()
        friendIsAt(59_000)
        assertEquals(SyncPolicy.SLOW, port.rates.single(), 0.0001f)

        // Casting starts: there is no speed control on a television, and every later request for
        // one reaches a receiver that ignores it.
        port.supportsRate = false
        friendIsAt(59_000, seq = 21)

        assertEquals(SyncPolicy.NORMAL, port.rates.last(), 0.0001f)
    }

    @Test
    fun `a player with no speed control is jumped rather than nudged`() = sessionTest {
        port.supportsRate = false
        liveAsGuest()

        friendIsAt(58_500)
        runCurrent()

        assertTrue(port.rates.isEmpty())
        assertEquals(1, port.seeks.size)
    }

    @Test
    fun `a gap too small to be worth a jump is left alone on a player with no speed control`() =
        sessionTest {
            port.supportsRate = false
            live()

            friendIsAt(59_400)
            runCurrent()

            assertTrue(port.rates.isEmpty())
            assertTrue(port.seeks.isEmpty())
        }

    @Test
    fun `a frame that would not decode is one frame's problem`() = sessionTest {
        live()

        transport.deliver(TogetherFailed(TogetherFailureReason.TAMPERED))
        runCurrent()

        assertTrue(session.state.value is SessionState.Live)
        assertNull((session.state.value as? SessionState.Lost)?.reason)
        assertFalse(port.pauses > 0)
    }
}
