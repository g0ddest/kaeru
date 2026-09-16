package app.kaeru.data.together

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.WatchTogetherTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every clock a shared viewing runs on, in one place so the two halves can be read against each
 * other, and injected rather than constant so a test does not have to sit out a production wait.
 */
data class TogetherTimeouts(
    /** Host: from putting a link in a chat to somebody knocking on the port. */
    val acceptMs: Int = 10_000,

    /** Guest: dialling an address on the same Wi-Fi, which answers in milliseconds or not at all. */
    val connectMs: Int = 5_000,

    /**
     * Either side: silence on a channel that is supposed to carry a ping every [pingMs]. Long
     * enough to ride out a tunnel, short enough that a phone that walked out of the house is
     * noticed while the other viewer still cares.
     */
    val idleMs: Int = 60_000,

    /** How often the session above sends a [TogetherMessage.Ping]. No transport sends its own. */
    val pingMs: Long = 5_000,

    /** Relay: the waits between attempts to get back, each one longer than the last. */
    val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),

    /** Relay: how long reconnecting goes on before the session is called over. */
    val reconnectBudgetMs: Long = 30_000,
)

/** Where a LAN endpoint is actually dialled — the address in the link, everywhere but a test. */
fun interface TogetherEndpoints {
    fun resolve(endpoint: LanEndpoint): InetSocketAddress
}

@Singleton
class LanTogetherEndpoints @Inject constructor() : TogetherEndpoints {
    /** The host is a validated IPv4 literal by the time it gets here, so nothing is resolved. */
    override fun resolve(endpoint: LanEndpoint) = InetSocketAddress(endpoint.host, endpoint.port)
}

/**
 * Two phones on one Wi-Fi, talking down a socket with nothing in between.
 *
 * The shape is the television pairing server's, for the same reasons: a port the operating system
 * picks, one peer, every read bounded in bytes and in time. What is different is that this one
 * stays open for the length of an episode rather than for one request, so the frames are
 * length-prefixed — four bytes, big-endian, then that many bytes of sealed frame — because a TCP
 * stream has no idea where one message stops.
 *
 * A length larger than a frame may ever be is the one error this cannot carry on from. A bad tag
 * is one frame's problem and the next frame is still where it should be; a length that is a lie
 * means the stream cannot be found again, so the collector is told and the connection ends.
 */
@Singleton
class LanSocketTransport @Inject constructor(
    private val addresses: LanAddresses,
    private val endpoints: TogetherEndpoints,
    private val timeouts: TogetherTimeouts,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : WatchTogetherTransport {

    private val _state = MutableStateFlow(ConnectionState.CLOSED)
    override val state = _state.asStateFlow()

    private class Live(val socket: Socket, val out: DataOutputStream, val key: ByteArray)

    private val lock = Any()
    private var server: ServerSocket? = null
    private var live: Live? = null

    /**
     * What went into the link, remembered until [close].
     *
     * Asking twice has to answer the same thing: the port stops being listened on the moment the
     * friend arrives, and a second call that bound a fresh one would name a port that nothing is
     * on — in a link that has already been sent.
     */
    private var advertised: LanEndpoint? = null

    /** Writes are serialised because both the session and its ping loop send from their own coroutines. */
    private val writes = Mutex()
    private val random = SecureRandom()

    /**
     * Binding is what this answers with, so the host can put a port in a link before anybody is
     * listening for messages on it. The port is held until [close].
     */
    override fun hostEndpoint(): LanEndpoint? {
        synchronized(lock) { advertised }?.let { return it }
        val host = addresses.siteLocalIpv4() ?: return null
        val socket = bound() ?: return null
        return LanEndpoint(host, socket.localPort).also { synchronized(lock) { advertised = it } }
    }

    override fun connect(link: RoomLink, asHost: Boolean): Flow<Result<TogetherMessage>> = flow {
        _state.value = ConnectionState.CONNECTING
        val socket = (if (asHost) accept() else dial(link.lan)).getOrElse { failure ->
            emit(Result.failure(failure))
            return@flow
        }
        socket.tcpNoDelay = true
        socket.soTimeout = minOf(POLL_MS, timeouts.idleMs)
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        synchronized(lock) {
            live = Live(socket, out, link.key)
            // One peer. Whoever got here first is the friend; a second knock finds nothing.
            closeServerLocked()
        }
        _state.value = ConnectionState.CONNECTED
        read(socket, link.key)
    }
        .flowOn(dispatcher)
        // Downstream of `flowOn` on purpose: this runs the moment the collector goes away, and
        // closing the socket is the only thing that gets the reading thread out of a blocking
        // read. Without it a cancelled session leaves a thread parked until the idle deadline.
        .onCompletion { shutdown() }

    /**
     * The read loop, and the reason it polls rather than simply blocking.
     *
     * A socket read cannot be interrupted — `Thread.interrupt` does nothing to one — and `flowOn`
     * will not let the collector finish until this producer has, so a session cancelled while
     * parked in a blocking read would hold the thread, the socket and the port until the idle
     * deadline. Seconds in a test; a minute on a phone whose viewer has already left the player.
     *
     * So the socket's own timeout is a quarter of a second and the idle deadline is counted across
     * those, which costs four syscalls a second on a channel that is quiet and gives back a
     * cancelled session in the time it takes to notice.
     */
    private suspend fun FlowCollector<Result<TogetherMessage>>.read(socket: Socket, key: ByteArray) {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(LENGTH_BYTES)
        while (currentCoroutineContext().isActive) {
            when (fill(input, header)) {
                Filled.DONE -> Unit
                // The peer hung up, or this side closed. Nothing to report and nothing to read.
                Filled.ENDED -> return
                Filled.SILENT -> {
                    // Nothing at all for a minute, on a channel that should carry a ping every
                    // five seconds. The friend's phone is asleep, or gone.
                    emit(failure(TogetherFailureReason.UNREACHABLE))
                    return
                }
            }
            val length = lengthOf(header)
            if (length <= 0 || length > TogetherCodec.MAX_FRAME_BYTES) {
                // A length that is a lie cannot be skipped past: whatever follows it is no longer
                // findable as a frame, so this is the one error the connection does not survive.
                emit(failure(TogetherFailureReason.FRAME_TOO_LARGE))
                return
            }
            val frame = ByteArray(length)
            if (fill(input, frame) != Filled.DONE) return
            emit(TogetherCodec.decode(frame, key))
        }
    }

    private enum class Filled { DONE, ENDED, SILENT }

    /** Reads exactly [into]`.size` bytes, or says why it could not. Partial reads are resumed. */
    private suspend fun fill(input: InputStream, into: ByteArray): Filled {
        var read = 0
        var silentMs = 0
        val poll = minOf(POLL_MS, timeouts.idleMs)
        while (read < into.size) {
            if (!currentCoroutineContext().isActive) return Filled.ENDED
            val got = try {
                input.read(into, read, into.size - read)
            } catch (silence: SocketTimeoutException) {
                silentMs += poll
                if (silentMs >= timeouts.idleMs) return Filled.SILENT
                continue
            } catch (end: IOException) {
                return Filled.ENDED
            }
            if (got < 0) return Filled.ENDED
            read += got
            silentMs = 0
        }
        return Filled.DONE
    }

    private fun lengthOf(header: ByteArray): Int =
        ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)

    override suspend fun send(message: TogetherMessage) {
        val open = synchronized(lock) { live } ?: throw TogetherFailed(TogetherFailureReason.DISCONNECTED)
        val frame = TogetherCodec.encode(message, open.key, TogetherCodec.newNonce(random))
        withContext(dispatcher) {
            writes.withLock {
                try {
                    open.out.writeInt(frame.size)
                    open.out.write(frame)
                    open.out.flush()
                } catch (broken: IOException) {
                    throw TogetherFailed(TogetherFailureReason.UNREACHABLE, broken)
                }
            }
        }
    }

    override suspend fun close() = shutdown()

    private fun shutdown() {
        synchronized(lock) {
            live?.let { runCatching { it.socket.close() } }
            live = null
            advertised = null
            closeServerLocked()
        }
        _state.value = ConnectionState.CLOSED
    }

    /** Caller holds [lock]. */
    private fun closeServerLocked() {
        runCatching { server?.close() }
        server = null
    }

    private fun bound(): ServerSocket? = synchronized(lock) {
        server?.takeIf { !it.isClosed }
            ?: runCatching {
                // `SO_REUSEADDR` off, as on the television: with it on, a wildcard bind shares a
                // port something else already holds on one address, and a friend's connection
                // would reach that other listener instead.
                ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(ANY_FREE_PORT), BACKLOG)
                }
            }.getOrNull()?.also { server = it }
    }

    private fun accept(): Result<Socket> {
        val listening = bound() ?: return Result.failure(TogetherFailed(TogetherFailureReason.UNREACHABLE))
        return runCatching {
            listening.soTimeout = timeouts.acceptMs
            listening.accept()
        }.fold(
            { Result.success(it) },
            { Result.failure(TogetherFailed(TogetherFailureReason.UNREACHABLE, it)) },
        )
    }

    /**
     * The address is checked before anything is opened, and against the ranges a home router hands
     * out rather than against whether it happens to answer. A link is a thing anybody can send.
     */
    private fun dial(endpoint: LanEndpoint?): Result<Socket> {
        if (endpoint == null || !PairingRequest.isLanAddress(endpoint.host)) {
            return Result.failure(TogetherFailed(TogetherFailureReason.BAD_LINK))
        }
        return runCatching {
            val socket = Socket()
            try {
                socket.connect(endpoints.resolve(endpoint), timeouts.connectMs)
            } catch (failed: IOException) {
                runCatching { socket.close() }
                throw failed
            }
            socket
        }.fold(
            { Result.success(it) },
            { Result.failure(TogetherFailed(TogetherFailureReason.UNREACHABLE, it)) },
        )
    }

    private fun failure(reason: TogetherFailureReason, cause: Throwable? = null): Result<TogetherMessage> =
        Result.failure(TogetherFailed(reason, cause))

    private companion object {
        const val ANY_FREE_PORT = 0
        const val BACKLOG = 1
        const val LENGTH_BYTES = 4

        /** How often a quiet read comes up for air, so a cancelled session is given back at once. */
        const val POLL_MS = 250
    }
}
