package app.kaeru.data.together

import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.RoomLink
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

    private fun frame(message: TogetherMessage) =
        TogetherCodec.encode(message, link.key, TogetherCodec.newNonce(random)).toByteString()

    private fun decode(bytes: ByteString) = TogetherCodec.decode(bytes.toByteArray(), link.key)

    private fun reasonOf(result: Result<*>) = (result.exceptionOrNull() as? TogetherFailed)?.reason

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
    fun `a build with no relay in it says so instead of dialling nowhere`() = runBlocking<Unit> {
        transport = relayAt("")

        val first = soon { transport.connect(link, asHost = false).first() }

        assertTrue(first.exceptionOrNull() is RelayNotConfigured)
        assertEquals(ConnectionState.CLOSED, transport.state.first())
        assertEquals(0, server.requestCount)
    }
}
