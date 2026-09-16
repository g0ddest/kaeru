package app.kaeru.data.together

import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.Side
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RelayTransportTest {
    private val random = SecureRandom()
    private val server = MockWebServer()
    private val link = RoomLink.random(random)

    /**
     * Collectors live here rather than in the test's own `runBlocking`, which would not return
     * until every one of them had finished — and a state flow never does.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = CopyOnWriteArrayList<ConnectionState>()

    private val ROOM_IDLE = 4408
    private val ROOM_FULL = 4409
    private val FRAME_TOO_LARGE = 4413

    /** Compressed to milliseconds; the shipped seconds would make this a half-minute test. */
    private val timeouts = TogetherTimeouts(
        backoffMs = listOf(300, 300, 300, 300),
        reconnectBudgetMs = 1_000,
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var transport: RelayTransport

    /** The far end of the socket: every connection it accepts, and everything said into it. */
    private class Relay : WebSocketListener() {
        val sockets = Channel<WebSocket>(Channel.UNLIMITED)
        val heard = Channel<ByteString>(Channel.UNLIMITED)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.trySend(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            heard.trySend(bytes)
        }
    }

    @Before
    fun setUp() {
        server.start()
        transport = relayAt(server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        scope.coroutineContext.job.cancelAndJoin()
        transport.close()
        server.shutdown()
    }

    private fun relayAt(base: String) = RelayTransport(client, base, timeouts, Dispatchers.IO)

    private fun inbox(): Channel<Result<TogetherMessage>> {
        val channel = Channel<Result<TogetherMessage>>(Channel.UNLIMITED)
        scope.launch { transport.state.collect(states::add) }
        scope.launch {
            transport.connect(link, asHost = false).collect(channel::send)
            channel.close()
        }
        return channel
    }

    private fun upgrade(): Relay = Relay().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }

    private suspend fun <T> soon(block: suspend () -> T): T = withTimeout(15_000) { block() }

    /** The transport under test joins as a guest, so its friend on the far end is the host. */
    private fun frame(message: TogetherMessage) =
        TogetherCodec.encode(message, link, Side.HOST, TogetherCodec.newNonce(random)).toByteString()

    private fun decode(bytes: ByteString) = TogetherCodec.decode(bytes.toByteArray(), link, Side.GUEST)

    private fun reasonOf(result: Result<*>) = (result.exceptionOrNull() as? TogetherFailed)?.reason

    /** Everything the flow emitted before it ended; the channel closes when the collector does. */
    private suspend fun drain(heard: Channel<Result<TogetherMessage>>): List<Result<TogetherMessage>> =
        buildList { for (item in heard) add(item) }

    @Test
    fun `the room is the path, so there is no first message announcing it`() = runBlocking<Unit> {
        val relay = upgrade()

        inbox()
        soon { relay.sockets.receive() }
        transport.send(TogetherMessage.Bye(seq = 1))

        assertEquals("/w/${link.roomId}", server.takeRequest().path)
        // The first thing the relay ever hears is a message, not a join.
        assertEquals(TogetherMessage.Bye(seq = 1), decode(soon { relay.heard.receive() }).getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `what one side sends, the other side reads`() = runBlocking<Unit> {
        val relay = upgrade()
        val heard = inbox()

        val socket = soon { relay.sockets.receive() }
        val chat = TogetherMessage.Chat("Стоп, что?", seq = 5)
        socket.send(frame(chat))

        assertEquals(chat, soon { heard.receive() }.getOrThrow())
        assertEquals(ConnectionState.CONNECTED, transport.state.first())
    }

    @Test
    fun `a frame the relay mangled is one frame's problem, not the session's`() = runBlocking<Unit> {
        val relay = upgrade()
        val heard = inbox()

        val socket = soon { relay.sockets.receive() }
        socket.send(ByteArray(64) { it.toByte() }.toByteString())
        socket.send(frame(TogetherMessage.Bye(seq = 2)))

        assertEquals(TogetherFailureReason.TAMPERED, reasonOf(soon { heard.receive() }))
        assertEquals(TogetherMessage.Bye(seq = 2), soon { heard.receive() }.getOrThrow())
        assertEquals(ConnectionState.CONNECTED, transport.state.first())
    }

    @Test
    fun `a dropped connection is picked back up, and what was said meanwhile still arrives`() = runBlocking<Unit> {
        val first = upgrade()
        val second = upgrade()
        inbox()

        val dropped = soon { first.sockets.receive() }
        soon { transport.state.first { it == ConnectionState.CONNECTED } }
        dropped.close(1001, null)
        soon { transport.state.first { it == ConnectionState.RECONNECTING } }
        transport.send(TogetherMessage.Play(positionMs = 5_000, seq = 7))

        soon { second.sockets.receive() }
        assertEquals(
            TogetherMessage.Play(positionMs = 5_000, seq = 7),
            decode(soon { second.heard.receive() }).getOrThrow(),
        )
        soon { transport.state.first { it == ConnectionState.CONNECTED } }
        assertEquals(2, server.requestCount)
        assertTrue(states.containsAll(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)))
    }

    @Test
    fun `the backlog is bounded, and it is the newest actions that survive`() = runBlocking<Unit> {
        val first = upgrade()
        val second = upgrade()
        inbox()

        val dropped = soon { first.sockets.receive() }
        soon { transport.state.first { it == ConnectionState.CONNECTED } }
        dropped.close(1001, null)
        soon { transport.state.first { it == ConnectionState.RECONNECTING } }
        (1..RelayTransport.MAX_BUFFERED + 6).forEach { transport.send(TogetherMessage.Seek(positionMs = it * 1_000L, seq = it.toLong())) }

        soon { second.sockets.receive() }
        val arrived = (1..RelayTransport.MAX_BUFFERED).map { decode(soon { second.heard.receive() }).getOrThrow() }

        assertEquals(TogetherMessage.Seek(positionMs = 7_000, seq = 7), arrived.first())
        assertEquals(RelayTransport.MAX_BUFFERED, arrived.size)
    }

    @Test
    fun `a relay that will not come back is given up on rather than dialled for ever`() = runBlocking<Unit> {
        val relay = upgrade()
        repeat(20) { server.enqueue(MockResponse().setResponseCode(503)) }
        val heard = inbox()

        val dropped = soon { relay.sockets.receive() }
        soon { transport.state.first { it == ConnectionState.CONNECTED } }
        dropped.close(1001, null)

        // The channel closes when the flow completes, so draining it yields every emission there was.
        val emitted = mutableListOf<Result<TogetherMessage>>()
        soon { for (item in heard) emitted += item }

        assertEquals(TogetherFailureReason.UNREACHABLE, reasonOf(emitted.last()))
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        val thrown = runCatching { transport.send(TogetherMessage.Bye(seq = 1)) }.exceptionOrNull()
        assertEquals(TogetherFailureReason.DISCONNECTED, (thrown as? TogetherFailed)?.reason)
    }

    @Test
    fun `a third phone is told the room is taken, and is not dialled again`() = runBlocking<Unit> {
        val relay = upgrade()
        // If the client retried, these would be taken; the point is that it does not.
        repeat(3) { upgrade() }
        val heard = inbox()

        soon { relay.sockets.receive() }.close(ROOM_FULL, "room full")

        val emitted = soon { drain(heard) }
        assertEquals(TogetherFailureReason.ROOM_FULL, reasonOf(emitted.last()))
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a frame the relay would not carry ends the session instead of being sent again`() = runBlocking<Unit> {
        val relay = upgrade()
        repeat(3) { upgrade() }
        val heard = inbox()

        soon { relay.sockets.receive() }.close(FRAME_TOO_LARGE, "frame too large")

        val emitted = soon { drain(heard) }
        assertEquals(TogetherFailureReason.FRAME_TOO_LARGE, reasonOf(emitted.last()))
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a room that sat empty for hours is simply over`() = runBlocking<Unit> {
        val relay = upgrade()
        repeat(3) { upgrade() }
        val heard = inbox()

        soon { relay.sockets.receive() }.close(ROOM_IDLE, "idle")

        val emitted = soon { drain(heard) }
        // Nothing to report: the room expired, and «связь потеряна» would be a lie.
        assertTrue(emitted.none { it.isFailure })
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the relay saying the friend left is not the connection dropping`() = runBlocking<Unit> {
        val relay = upgrade()
        val heard = inbox()

        val socket = soon { relay.sockets.receive() }
        socket.send("""{"type":"peer-left"}""")
        socket.send(frame(TogetherMessage.Chat("Я вернулся", seq = 3)))

        assertEquals(TogetherMessage.PeerLeft(), soon { heard.receive() }.getOrThrow())
        // The slot is reusable, so the socket stays up and the same friend can come back into it.
        assertEquals(TogetherMessage.Chat("Я вернулся", seq = 3), soon { heard.receive() }.getOrThrow())
        assertEquals(ConnectionState.CONNECTED, transport.state.first())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `anything else the relay says in words is ignored`() = runBlocking<Unit> {
        val relay = upgrade()
        val heard = inbox()

        val socket = soon { relay.sockets.receive() }
        socket.send("""{"type":"something-this-build-predates"}""")
        socket.send("not json at all")
        socket.send(frame(TogetherMessage.Bye(seq = 4)))

        assertEquals(TogetherMessage.Bye(seq = 4), soon { heard.receive() }.getOrThrow())
        assertEquals(ConnectionState.CONNECTED, transport.state.first())
    }

    @Test
    fun `a relay that answers its health check is told apart from one that does not`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(transport.healthy())
        assertEquals("/health", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(transport.healthy())

        // A build with no relay in it has nothing to probe, and says so without a request.
        assertFalse(relayAt("").healthy())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a session whose screen went away is closed, not left looking connected`() = runBlocking<Unit> {
        val relay = upgrade()
        val collecting = scope.launch { transport.connect(link, asHost = false).collect { } }

        soon { relay.sockets.receive() }
        soon { transport.state.first { it == ConnectionState.CONNECTED } }
        collecting.cancelAndJoin()

        soon { transport.state.first { it == ConnectionState.CLOSED } }
        val thrown = runCatching { transport.send(TogetherMessage.Bye(seq = 1)) }.exceptionOrNull()
        assertEquals(TogetherFailureReason.DISCONNECTED, (thrown as? TogetherFailed)?.reason)
    }


    @Test
    fun `a build with no relay in it says so instead of dialling nowhere`() = runBlocking<Unit> {
        transport = relayAt("")

        val first = soon { transport.connect(link, asHost = false).first() }

        assertTrue(first.exceptionOrNull() is RelayNotConfigured)
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        assertEquals(0, server.requestCount)
    }
}
