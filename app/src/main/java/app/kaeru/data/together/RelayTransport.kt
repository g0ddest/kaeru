package app.kaeru.data.together

import app.kaeru.di.IoDispatcher
import app.kaeru.di.TogetherClient
import app.kaeru.di.TogetherRelayUrl
import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.WatchTogetherTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two phones on different networks, talking through a stranger's machine.
 *
 * Neither phone has an address the other can reach — mobile operators put thousands of subscribers
 * behind one public address, and a home router drops anything nobody asked for. So both sides dial
 * out instead, to the same room on a relay, and it copies bytes between them.
 *
 * The room is the path: `wss://<relay>/w/<roomId>`. There is no hello, no join, no first message
 * that says which room this is — the URL already said it, and a protocol with a join frame has a
 * state before the connection is usable, which is one more thing to get wrong on every reconnect.
 *
 * What the relay can see is the room name, two addresses and how many bytes went by. Not the
 * anime, not the episode, not a word of what was said: everything inside a frame is sealed under
 * the key that never left the two phones.
 *
 * Dropping is ordinary. A phone changes cell, a screen locks, a train goes into a tunnel — so a
 * dropped socket is not the end of a session, it is [ConnectionState.RECONNECTING] and a handful
 * of attempts over half a minute. What was said meanwhile is kept and sent when the socket comes
 * back, newest first to survive, because the protocol's own rule is that the last action wins.
 */
@Singleton
class RelayTransport @Inject constructor(
    @param:TogetherClient private val client: OkHttpClient,
    @param:TogetherRelayUrl private val baseUrl: String,
    private val timeouts: TogetherTimeouts,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : WatchTogetherTransport {

    private val _state = MutableStateFlow(ConnectionState.CLOSED)
    override val state = _state.asStateFlow()

    private val lock = Any()

    /** The socket that is open, or null while there is none. Only a live one is written to. */
    private var live: WebSocket? = null

    /** The one being dialled, kept only so [close] can cut a handshake short. */
    private var dialing: WebSocket? = null
    private var key: ByteArray? = null
    private var closedByUs = false
    private val backlog = ArrayDeque<ByteArray>()
    private val random = SecureRandom()

    override fun connect(link: RoomLink, asHost: Boolean): Flow<Result<TogetherMessage>> = callbackFlow {
        if (baseUrl.isBlank()) {
            // Not a relay that is down — a build assembled without one. Saying so at once is the
            // difference between «попробуйте позже» and «этой сборкой так нельзя».
            _state.value = ConnectionState.CLOSED
            trySend(Result.failure(RelayNotConfigured()))
        } else {
            synchronized(lock) {
                key = link.key
                closedByUs = false
                backlog.clear()
            }
            keepConnected(link, this)
        }
        close()
        awaitClose { cut() }
    }.flowOn(dispatcher)

    private suspend fun keepConnected(link: RoomLink, out: ProducerScope<Result<TogetherMessage>>) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}$ROOM_PATH${link.roomId}").build()
        var attempt = 0
        var giveUpAt = 0L
        while (out.isActive) {
            _state.value = if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
            val opened = AtomicBoolean(false)
            val died = CompletableDeferred<Unit>()
            val socket = client.newWebSocket(request, Peer(link.key, out, opened, died))
            synchronized(lock) { dialing = socket }
            died.await()
            synchronized(lock) {
                dialing = null
                live = null
            }
            if (synchronized(lock) { closedByUs } || !out.isActive) return
            if (opened.get()) {
                // A socket that worked is a fresh start: the half-minute is per outage, not per
                // session, or a long evening would run out of it.
                attempt = 0
                giveUpAt = 0
            }
            if (giveUpAt == 0L) giveUpAt = System.currentTimeMillis() + timeouts.reconnectBudgetMs
            val wait = timeouts.backoffMs.getOrElse(attempt) { timeouts.backoffMs.lastOrNull() ?: 0 }
            attempt++
            if (System.currentTimeMillis() + wait > giveUpAt) break
            _state.value = ConnectionState.RECONNECTING
            delay(wait)
        }
        _state.value = ConnectionState.CLOSED
        out.trySend(Result.failure(TogetherFailed(TogetherFailureReason.UNREACHABLE)))
    }

    /**
     * Buffers rather than throws while the socket is away, because a pause pressed during a
     * two-second reconnect is a pause the viewer meant. Throws once there is no session at all.
     */
    override suspend fun send(message: TogetherMessage) {
        val room = synchronized(lock) { key }
        if (room == null || _state.value == ConnectionState.CLOSED) {
            throw TogetherFailed(TogetherFailureReason.DISCONNECTED)
        }
        val frame = TogetherCodec.encode(message, room, TogetherCodec.newNonce(random))
        val open = synchronized(lock) {
            live ?: run {
                backlog.addLast(frame)
                while (backlog.size > MAX_BUFFERED) backlog.removeFirst()
                null
            }
        }
        open?.send(frame.toByteString())
    }

    override suspend fun close() {
        synchronized(lock) { closedByUs = true }
        cut()
        _state.value = ConnectionState.CLOSED
    }

    override fun hostEndpoint(): LanEndpoint? = null

    private fun cut() {
        val (open, pending) = synchronized(lock) {
            val pair = live to dialing
            live = null
            dialing = null
            backlog.clear()
            pair
        }
        // `cancel` rather than `close`: a closing handshake waits for an answer from a relay that
        // may be the reason this is being cut in the first place.
        runCatching { open?.cancel() }
        runCatching { pending?.cancel() }
    }

    private inner class Peer(
        private val roomKey: ByteArray,
        private val out: ProducerScope<Result<TogetherMessage>>,
        private val opened: AtomicBoolean,
        private val died: CompletableDeferred<Unit>,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.set(true)
            synchronized(lock) {
                live = webSocket
                // Inside the lock so a send arriving now queues behind the backlog rather than
                // jumping in front of actions the viewer took first.
                while (backlog.isNotEmpty()) webSocket.send(backlog.removeFirst().toByteString())
            }
            _state.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            out.trySend(TogetherCodec.decode(bytes.toByteArray(), roomKey))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(NORMAL_CLOSURE, null) }
            died.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            died.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            died.complete(Unit)
        }
    }

    companion object {
        /**
         * Enough to hold everything a viewer can do in half a minute of scrubbing, and bounded so
         * a long outage cannot fill memory. Past it the oldest go: the last action is the one that
         * counts, and replaying a stale seek on reconnect would undo what was done after it.
         */
        const val MAX_BUFFERED = 64

        private const val ROOM_PATH = "/w/"
        private const val NORMAL_CLOSURE = 1000
    }
}
