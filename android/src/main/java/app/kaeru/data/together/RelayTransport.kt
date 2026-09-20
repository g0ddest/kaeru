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
import app.kaeru.domain.together.Side
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.TransportFactory
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * One of these carries one session; [TransportFactory] makes a fresh one for the next.
 *
 * Dropping is ordinary. A phone changes cell, a screen locks, a train goes into a tunnel — so a
 * dropped socket is not the end of a session, it is [ConnectionState.RECONNECTING] and a handful
 * of attempts over half a minute. What was said meanwhile is kept and sent when the socket comes
 * back, newest first to survive, because the protocol's own rule is that the last action wins.
 */
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

    /** Completed when the socket that is up has finished dying, so a goodbye can be waited for. */
    private var ended: CompletableDeferred<Unit>? = null
    private var room: RoomLink? = null
    private var mine: Side = Side.GUEST
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
            // A copy of the key, so [cut] has something of its own to wipe rather than reaching
            // into the link the caller is still holding.
            val owned = link.copy(key = link.key.copyOf())
            synchronized(lock) {
                room = owned
                mine = if (asHost) Side.HOST else Side.GUEST
                closedByUs = false
                backlog.clear()
            }
            TogetherLog.write(
                "connect room=${link.roomId} as=${if (asHost) "host" else "guest"} " +
                    "host=${baseUrl.substringAfter("://").substringBefore("/")}",
            )
            keepConnected(owned, this)
        }
        channel.close()
        awaitClose { cut() }
    }
        .flowOn(dispatcher)
        // Downstream of `flowOn`, the way the LAN transport does it, and for a reason the
        // `awaitClose` above cannot cover: a cancelled collector unwinds the producer out of
        // whatever it was awaiting, so the body never reaches its own cleanup. This runs either
        // way, and it is what makes a session end when the screen it was on goes away.
        .onCompletion { cut() }

    private suspend fun keepConnected(link: RoomLink, out: ProducerScope<Result<TogetherMessage>>) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}$ROOM_PATH${link.roomId}").build()
        var attempt = 0
        var waited = 0L
        var everConnected = false
        while (out.isActive) {
            // «Подключаемся» is only ever true once. After a session has been up, everything that
            // follows is «связь потеряна, пробуем снова», and a screen that says otherwise reads
            // as though the friend were never there.
            _state.value =
                if (attempt == 0 && !everConnected) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
            val opened = AtomicBoolean(false)
            val closeCode = AtomicInteger(NO_CLOSE_CODE)
            val died = CompletableDeferred<Unit>()
            val socket = client.newWebSocket(request, Peer(link, mine.other, out, opened, closeCode, died))
            synchronized(lock) {
                dialing = socket
                ended = died
            }
            died.await()
            synchronized(lock) {
                dialing = null
                live = null
                ended = null
            }
            if (synchronized(lock) { closedByUs } || !out.isActive) return
            // Three of the relay's close codes are answers, not accidents, and dialling again would
            // only get the same one. They are checked before anything else, including before a
            // socket that opened resets the budget — a room refusing a third peer opens first.
            val code = closeCode.get()
            TogetherLog.write(
                when {
                    code in TERMINAL_CLOSES -> "socket refused close=$code"
                    opened.get() -> "socket lost close=$code; reconnecting"
                    else -> "dial failed close=$code; retrying"
                },
            )
            if (code in TERMINAL_CLOSES) {
                _state.value = ConnectionState.CLOSED
                refusalOf(code)?.let { out.trySend(Result.failure(TogetherFailed(it))) }
                return
            }
            if (opened.get()) {
                // A socket that worked is a fresh start: the half-minute is per outage, not per
                // session, or a long evening would run out of it.
                attempt = 0
                waited = 0
                everConnected = true
            }
            val step = timeouts.backoffMs.getOrElse(attempt) { timeouts.backoffMs.lastOrNull() ?: 0 }
            attempt++
            // What is left of the half-minute, counted in the waits themselves rather than off the
            // wall clock — they are the same thing on a phone, and only the former can be tested
            // without sitting through it. The last wait is clipped to the remainder instead of
            // being abandoned for overshooting: with 1/2/4/8/16 the fifth would land at 31 s, and
            // breaking there gave up at 15 — half the window the spec promises, with the last step
            // of the schedule unreachable.
            val remaining = timeouts.reconnectBudgetMs - waited
            if (remaining <= 0) break
            val wait = minOf(step, remaining)
            waited += wait
            _state.value = ConnectionState.RECONNECTING
            delay(wait)
        }
        _state.value = ConnectionState.CLOSED
        TogetherLog.write("gave up dialling after ${timeouts.reconnectBudgetMs / 1000}s")
        out.trySend(Result.failure(TogetherFailed(TogetherFailureReason.UNREACHABLE)))
    }

    /**
     * Buffers rather than throws while the socket is away, because a pause pressed during a
     * two-second reconnect is a pause the viewer meant. Throws once there is no session at all.
     */
    override suspend fun send(message: TogetherMessage) {
        val (link, side) = synchronized(lock) { room to mine }
        if (link == null || _state.value == ConnectionState.CLOSED) {
            throw TogetherFailed(TogetherFailureReason.DISCONNECTED)
        }
        // Sealing a 32 KB voice slice is not main-thread work.
        val frame = withContext(dispatcher) {
            TogetherCodec.encode(message, link, side, TogetherCodec.newNonce(random))
        }
        val open = synchronized(lock) {
            live ?: run {
                backlog.addLast(frame)
                while (backlog.size > MAX_BUFFERED) backlog.removeFirst()
                null
            }
        }
        open?.send(frame.toByteString())
    }

    /**
     * Closes politely, because the last thing written to this socket is usually a goodbye.
     *
     * `send` only enqueues, and OkHttp's `cancel` throws the queue away — so a session ending with
     * `Bye` on the wire delivered nothing, and the friend saw a bare socket drop and waited half a
     * minute to be told the connection was lost instead of being told somebody left. `close`
     * transmits what is queued and then the close frame; the grace below is the ceiling on how
     * long that is worth waiting for, after which the socket is cut the way [cut] would.
     */
    override suspend fun close() {
        val (open, finished) = synchronized(lock) {
            closedByUs = true
            live to ended
        }
        if (open != null) {
            runCatching { open.close(NORMAL_CLOSURE, null) }
            // Not a handshake this waits out: the queue is flushed by the writer thread, and a
            // relay that has stopped answering must not hold a screen that is going away.
            withTimeoutOrNull(GOODBYE_GRACE_MS) { finished?.await() }
            // The close frame is out and has been either answered or waited for; the socket may go.
            runCatching { open.cancel() }
        }
        cut()
        _state.value = ConnectionState.CLOSED
    }

    override fun hostEndpoint(): LanEndpoint? = null

    /**
     * Whether the relay is answering at all — a plain `GET /health`.
     *
     * Worth its own request because the two failures read completely differently to a viewer: a
     * relay that is down is «попробуйте позже», and a phone with no network is «нет сети». The
     * socket client's read timeout is deliberately infinite, so this borrows the connection pool
     * and puts a short deadline of its own on the call.
     */
    suspend fun healthy(): Boolean {
        if (baseUrl.isBlank()) return false
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}$HEALTH_PATH").build()
        return withContext(dispatcher) {
            runCatching { probe.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
    }

    private val probe: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(PROBE_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun cut() {
        val closedGracefully = synchronized(lock) { closedByUs }
        val (open, pending) = synchronized(lock) {
            val pair = live to dialing
            live = null
            dialing = null
            backlog.clear()
            // Best effort, and no more: a JVM copies arrays wherever it likes, so this wipes the
            // one copy this object is known to hold and claims nothing about the rest.
            room?.key?.fill(0)
            room = null
            pair
        }
        // `cancel` rather than `close`: a closing handshake waits for an answer from a relay that
        // may be the reason this is being cut in the first place.
        //
        // Except when this side already asked to close politely. That path has sent its close
        // frame, waited for it and cancelled the socket itself — and this runs again a moment
        // later from the collector's own teardown, where cancelling would throw away a goodbye
        // still in the write queue if the two ever raced.
        if (!closedGracefully) runCatching { open?.cancel() }
        runCatching { pending?.cancel() }
        // Said here rather than only in `close`, because the ordinary way a session ends is the
        // collector being cancelled with the screen it was on — and this is all that runs then.
        // Without it the state reports CONNECTED for the life of the transport and `send` buffers
        // into a backlog that nothing will ever flush, instead of saying there is nowhere to write.
        _state.value = ConnectionState.CLOSED
    }

    private inner class Peer(
        private val link: RoomLink,
        /** The side the friend seals with. A frame bearing this one's own side is a reflection. */
        private val from: Side,
        private val out: ProducerScope<Result<TogetherMessage>>,
        private val opened: AtomicBoolean,
        private val closeCode: AtomicInteger,
        private val died: CompletableDeferred<Unit>,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.set(true)
            TogetherLog.write("dial ok")
            synchronized(lock) {
                live = webSocket
                // Inside the lock so a send arriving now queues behind the backlog rather than
                // jumping in front of actions the viewer took first.
                while (backlog.isNotEmpty()) webSocket.send(backlog.removeFirst().toByteString())
            }
            _state.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Checked here as well as inside `decode`, because this is the first place the size is
            // known and it costs nothing. What it does not do is prevent the allocation: OkHttp
            // reads a whole message into memory before saying a word about it and offers no cap to
            // set, so a hostile relay can still make this process hold one oversized message. That
            // is the residual risk of relaying through somebody else's machine, and it is bounded
            // by the relay's own 64 KiB limit for as long as the relay is the one we deployed.
            if (bytes.size > TogetherCodec.MAX_FRAME_BYTES) {
                out.trySend(Result.failure(TogetherFailed(TogetherFailureReason.FRAME_TOO_LARGE)))
                return
            }
            out.trySend(TogetherCodec.decode(bytes.toByteArray(), link, from))
        }

        /**
         * The rule is the whole of it: binary is the friend, text is the relay.
         *
         * The relay has exactly one thing to say, and saying it does not end anything — the room
         * keeps the freed seat, so this socket stays up and the same friend can walk back into it.
         * Anything else in words is from a relay newer than this build and is ignored.
         */
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (peerLeft(text)) {
                TogetherLog.write("relay says peer-left")
                out.trySend(Result.success(TogetherMessage.PeerLeft()))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.set(code)
            runCatching { webSocket.close(NORMAL_CLOSURE, null) }
            died.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.compareAndSet(NO_CLOSE_CODE, code)
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
        private const val HEALTH_PATH = "/health"
        private const val NORMAL_CLOSURE = 1000

        /** Long enough for a queued goodbye to reach the wire, short enough to be unnoticeable. */
        private const val GOODBYE_GRACE_MS = 1_000L
        private const val NO_CLOSE_CODE = -1
        private const val PROBE_SECONDS = 5L

        /** The relay's own codes are its HTTP status plus 4000. */
        private const val CLOSE_IDLE = 4408
        private const val CLOSE_ROOM_FULL = 4409
        private const val CLOSE_FRAME_TOO_LARGE = 4413

        private val TERMINAL_CLOSES = setOf(CLOSE_IDLE, CLOSE_ROOM_FULL, CLOSE_FRAME_TOO_LARGE)

        private const val PEER_LEFT = "peer-left"

        private val control = Json { ignoreUnknownKeys = true }

        /**
         * What to tell the viewer about a close that ends things. A room that expired after hours
         * of silence gets nothing: it is over, and «связь потеряна» would be a lie about why.
         */
        private fun refusalOf(code: Int): TogetherFailureReason? = when (code) {
            CLOSE_ROOM_FULL -> TogetherFailureReason.ROOM_FULL
            CLOSE_FRAME_TOO_LARGE -> TogetherFailureReason.FRAME_TOO_LARGE
            else -> null
        }

        private fun peerLeft(text: String): Boolean =
            runCatching { control.decodeFromString<RelayControl>(text).type }.getOrNull() == PEER_LEFT
    }
}

/** The only shape the relay ever sends, and only ever as text. */
@Serializable
private data class RelayControl(val type: String? = null)
