package app.kaeru.data.together

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.TransportFactory
import app.kaeru.domain.together.Side
import app.kaeru.domain.together.TogetherCodec
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.WatchTogetherTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
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
     * Host: how long somebody who has connected has to prove they hold the room key.
     *
     * Short, because the friend's first frame is already written by the time the socket is up. It
     * is a whole second and more than a round trip on any network where a direct socket works at
     * all, and a connector who misses it was not the friend.
     */
    val authMs: Int = 2_000,

    /**
     * Guest: how long the host has to answer the greeting.
     *
     * Far longer than [authMs], and not the same number by design. Vetting a connector is a network
     * question — the frame is already on its way or it is not. Answering a greeting is not: it
     * crosses into the session above, which has to hear the greeting, decide what it is watching
     * and compose a reply, and on a cold start it may resolve something first. Holding that to a
     * vet's two seconds would report a phone that was merely busy as one that was not there.
     */
    val greetMs: Int = 10_000,

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
 * One of these carries one session; [TransportFactory] makes a fresh one for the next.
 *
 * A length larger than a frame may ever be is the one error this cannot carry on from. A bad tag
 * is one frame's problem and the next frame is still where it should be; a length that is a lie
 * means the stream cannot be found again, so the collector is told and the connection ends.
 */
class LanSocketTransport @Inject constructor(
    private val addresses: LanAddresses,
    private val endpoints: TogetherEndpoints,
    private val timeouts: TogetherTimeouts,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : WatchTogetherTransport {

    private val _state = MutableStateFlow(ConnectionState.CLOSED)
    override val state = _state.asStateFlow()

    /** What this device is in this room, known from the moment [connect] is collected. */
    private class Session(val link: RoomLink, val mine: Side)

    private class Live(val socket: Socket, val out: DataOutputStream)

    private val lock = Any()
    private var server: ServerSocket? = null
    private var session: Session? = null
    private var live: Live? = null

    /**
     * What the session said before there was a socket to say it down.
     *
     * A guest's greeting is written while the connection is still being made, because the host will
     * not call anybody a friend until it arrives. Bounded because it is a queue somebody else fills:
     * a greeting is one frame, and anything past the cap is a session doing something odd.
     */
    private val queued = ArrayDeque<ByteArray>()

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

    /**
     * @param asHost whether this device is the one that made the link.
     *
     * A guest sends its first message straight away — it may do so the moment this returns, before
     * the flow is collected and before any socket exists, and the greeting is written as soon as
     * one does. That is not a
     * nicety: a host on the local network gives its one seat to nobody until a frame decrypts
     * under the room key, so a guest that waits to be told it is connected waits for ever. The
     * host answers in kind, which is what tells the guest it dialled a Kaeru and not something
     * else on the same address.
     */
    override fun connect(link: RoomLink, asHost: Boolean): Flow<Result<TogetherMessage>> {
        // A copy of the key, so [shutdown] has something of its own to wipe.
        val owned = link.copy(key = link.key.copyOf())
        // Set here rather than inside the flow, so that a greeting can be handed over the moment
        // this returns. The flow is cold and a caller collects it from wherever it likes; making
        // the first `send` wait for that to happen would be a race with no way to win it.
        synchronized(lock) {
            session = Session(owned, if (asHost) Side.HOST else Side.GUEST)
            queued.clear()
        }
        _state.value = ConnectionState.CONNECTING
        return flow { if (asHost) hostSession(owned) else guestSession(owned) }
        .flowOn(dispatcher)
        // Downstream of `flowOn` on purpose: this runs the moment the collector goes away, and
        // closing the socket is the only thing that gets the reading thread out of a blocking
        // read. Without it a cancelled session leaves a thread parked until the idle deadline.
            .onCompletion { shutdown() }
    }

    /**
     * Waiting for the friend, and giving the seat to nobody else.
     *
     * The port is advertised in a link, and a link travels through a chat application — so the
     * first device to connect is not necessarily the one it was sent to. Anything on the same
     * Wi-Fi can reach an advertised port, and handing it the session the moment `accept` returns
     * meant one stray connection could deny the whole evening to the actual friend, with the host's
     * screen cheerfully reading «подключено».
     *
     * So a connection is provisional: it has to deliver one frame that decrypts under the room key,
     * which only somebody holding the link can produce. And every connection is provisional *at the
     * same time*. Vetting them one after another was the same denial in slower clothes — a socket
     * that connects and says nothing costs the window [TogetherTimeouts.authMs], and five of them
     * spend the whole of it, so a friend knocking behind them is accepted by the kernel and never
     * heard. Now each connector is vetted in its own coroutine, the door keeps opening, and the
     * first frame that decrypts wins the seat and closes everything else.
     */
    private suspend fun FlowCollector<Result<TogetherMessage>>.hostSession(link: RoomLink) {
        val listening = bound() ?: run {
            emit(failure(TogetherFailureReason.UNREACHABLE))
            return
        }
        val friend = awaitFriend(listening, link, System.currentTimeMillis() + timeouts.acceptMs)
        if (friend == null) {
            emit(failure(TogetherFailureReason.UNREACHABLE))
            return
        }
        install(friend.socket)
        seated()
        emit(Result.success(friend.greeting))
        read(friend.input, link, from = Side.GUEST)
    }

    /** A connector that proved it holds the key, and the stream its first frame came off. */
    private class Vouched(
        val socket: Socket,
        val input: BufferedInputStream,
        val greeting: TogetherMessage,
    )

    private suspend fun awaitFriend(listening: ServerSocket, link: RoomLink, until: Long): Vouched? {
        // Every socket accepted, so that whatever does not win is closed on the way out — including
        // one accepted a moment before somebody else vouched.
        val provisional = CopyOnWriteArrayList<Socket>()
        val won = CompletableDeferred<Vouched?>()
        var friend: Vouched? = null
        try {
            coroutineScope {
                val accepting = launch {
                    val vetting = mutableListOf<Job>()
                    while (isActive && !won.isCompleted) {
                        val left = until - System.currentTimeMillis()
                        if (left <= 0) break
                        val peer = try {
                            listening.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            listening.accept()
                        } catch (nobody: IOException) {
                            // A timeout means nobody came; anything else means the socket is gone.
                            break
                        }
                        provisional += peer
                        vetting += launch { vet(peer, link, until, won) }
                    }
                    vetting.joinAll()
                    // The window is over and everything it let in has been heard out.
                    won.complete(null)
                }
                friend = won.await()
                // Closing the listener is what gets the accept loop out of its blocking wait; the
                // cancel alone would leave it parked there for the rest of the window.
                synchronized(lock) { closeServerLocked() }
                accepting.cancel()
            }
        } finally {
            provisional.forEach { if (it !== friend?.socket) runCatching { it.close() } }
        }
        return friend
    }

    private suspend fun vet(peer: Socket, link: RoomLink, until: Long, won: CompletableDeferred<Vouched?>) {
        var seated = false
        try {
            peer.tcpNoDelay = true
            peer.soTimeout = minOf(POLL_MS, timeouts.authMs)
            val input = BufferedInputStream(peer.getInputStream())
            // Its own deadline, and never one that outlives the window it sits inside — a connector
            // arriving with a second left has a second, not two.
            val deadline = minOf(System.currentTimeMillis() + timeouts.authMs, until)
            val vetted = vouched(input, link, from = Side.GUEST, budgetMs = timeouts.authMs, deadline = deadline)
            seated = vetted is Vetted.Friend && won.complete(Vouched(peer, input, vetted.greeting))
        } finally {
            if (!seated) runCatching { peer.close() }
        }
    }

    /**
     * Dialling the address in the link, and making the far end prove it is the friend.
     *
     * Smaller stakes than the host's door — a guest dialled one specific address out of a link
     * rather than advertising a port to a whole network — but the same question, and it has a real
     * answer: something else listening at that address can accept a connection and will never
     * produce a frame that opens. Rather than show a session that only ever yields `TAMPERED`, the
     * guest waits for one good frame and says so plainly if it does not come.
     */
    private suspend fun FlowCollector<Result<TogetherMessage>>.guestSession(link: RoomLink) {
        val socket = dial(link.lan).getOrElse { failure ->
            emit(Result.failure(failure))
            return
        }
        // Installed before the far end has proved anything, because installing is what sends the
        // greeting that gives it something to answer. The state stays CONNECTING until it does.
        //
        // So the greeting does reach whatever answered at that address, before anything is proved.
        // What that costs is bounded and deliberate: the frame is sealed under the room key with
        // `roomId ‖ GUEST` as its associated data, so an impostor gets ciphertext it cannot open.
        // What leaks is that a Kaeru guest dialled the address, and a length that tracks the length
        // of the viewer's display name. The alternative is an extra round trip before anybody can
        // say anything, and that is a worse trade for a feature whose whole point is being quick.
        install(socket)
        val input = BufferedInputStream(socket.getInputStream())
        val vetted = vouched(
            input,
            link,
            from = Side.HOST,
            budgetMs = timeouts.greetMs,
            deadline = System.currentTimeMillis() + timeouts.greetMs,
        )
        if (vetted !is Vetted.Friend) {
            emit(
                failure(
                    if (vetted is Vetted.Silent) TogetherFailureReason.UNREACHABLE
                    else TogetherFailureReason.TAMPERED,
                ),
            )
            return
        }
        seated()
        emit(Result.success(vetted.greeting))
        read(input, link, from = Side.HOST)
    }

    /**
     * What a first frame turned out to be. The two failures read differently to a viewer: nothing
     * answered, or something answered and it was not the friend.
     */
    private sealed interface Vetted {
        data class Friend(val greeting: TogetherMessage) : Vetted

        /** Too slow, or too quiet. Nothing arrived inside the deadline. */
        data object Silent : Vetted

        /** Something arrived: a length that is a lie, or bytes that will not open in this room. */
        data object Wrong : Vetted
    }

    /**
     * The first frame, and whether it proves anything.
     *
     * A frame bearing the reader's own side is a reflection rather than a greeting, and fails here
     * like any other frame that will not open.
     */
    private suspend fun vouched(
        input: BufferedInputStream,
        link: RoomLink,
        from: Side,
        budgetMs: Int,
        deadline: Long,
    ): Vetted {
        val header = ByteArray(LENGTH_BYTES)
        if (fill(input, header, budgetMs, deadline) != Filled.DONE) return Vetted.Silent
        val length = lengthOf(header)
        if (length <= 0 || length > TogetherCodec.MAX_FRAME_BYTES) return Vetted.Wrong
        val frame = ByteArray(length)
        if (fill(input, frame, budgetMs, deadline) != Filled.DONE) return Vetted.Silent
        val greeting = TogetherCodec.decode(frame, link, from).getOrNull() ?: return Vetted.Wrong
        return Vetted.Friend(greeting)
    }

    /**
     * There is a socket to write to now, so whatever the session said while there was not goes out
     * first, in the order it was said. Says nothing about whether the far end is the friend.
     */
    private fun install(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = minOf(POLL_MS, timeouts.idleMs)
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        synchronized(lock) {
            live = Live(socket, out)
            closeServerLocked()
            // Inside the lock, so a `send` arriving now queues behind the greeting rather than
            // overtaking it.
            while (queued.isNotEmpty()) write(out, queued.removeFirst())
        }
    }

    /** Only now is there somebody on the other end worth calling a friend. */
    private fun seated() {
        _state.value = ConnectionState.CONNECTED
    }

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
    private suspend fun FlowCollector<Result<TogetherMessage>>.read(
        input: BufferedInputStream,
        link: RoomLink,
        from: Side,
    ) {
        val header = ByteArray(LENGTH_BYTES)
        while (currentCoroutineContext().isActive) {
            when (fill(input, header, timeouts.idleMs)) {
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
            // A length that is a lie cannot be skipped past: whatever follows it is no longer
            // findable as a frame, so either of these ends the connection. They are told apart
            // because they read differently — one side sent something too big, or somebody who is
            // not speaking this protocol at all is writing into the socket.
            if (length > TogetherCodec.MAX_FRAME_BYTES) {
                emit(failure(TogetherFailureReason.FRAME_TOO_LARGE))
                return
            }
            if (length <= 0) {
                emit(failure(TogetherFailureReason.TAMPERED))
                return
            }
            val frame = ByteArray(length)
            if (fill(input, frame, timeouts.idleMs) != Filled.DONE) return
            emit(TogetherCodec.decodeFromPeer(frame, link, from.other))
        }
    }

    private enum class Filled { DONE, ENDED, SILENT }

    /**
     * Reads exactly [into]`.size` bytes, or says why it could not. Partial reads are resumed.
     *
     * [budgetMs] is how long an unbroken silence may last; any byte resets it, which is what makes
     * a live channel with a ping on it last all evening. [deadline] is the wall a caller can put up
     * in front of that: the provisional seat has one, so a connector cannot hold it open by
     * dribbling a byte every second and a half.
     */
    private suspend fun fill(
        input: InputStream,
        into: ByteArray,
        budgetMs: Int,
        deadline: Long = Long.MAX_VALUE,
    ): Filled {
        var read = 0
        var silentMs = 0
        val poll = minOf(POLL_MS, budgetMs)
        while (read < into.size) {
            if (!currentCoroutineContext().isActive) return Filled.ENDED
            if (System.currentTimeMillis() >= deadline) return Filled.SILENT
            val got = try {
                input.read(into, read, into.size - read)
            } catch (silence: SocketTimeoutException) {
                silentMs += poll
                if (silentMs >= budgetMs) return Filled.SILENT
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
        val mine = synchronized(lock) { session } ?: throw TogetherFailed(TogetherFailureReason.DISCONNECTED)
        withContext(dispatcher) {
            // Sealing a 32 KB voice slice is not main-thread work, so it happens here rather than
            // on the caller's thread.
            val frame = TogetherCodec.encode(message, mine.link, mine.mine, TogetherCodec.newNonce(random))
            val out = synchronized(lock) {
                live?.out ?: run {
                    queued.addLast(frame)
                    while (queued.size > MAX_QUEUED) queued.removeFirst()
                    null
                }
            } ?: return@withContext
            writes.withLock { write(out, frame) }
        }
    }

    private fun write(out: DataOutputStream, frame: ByteArray) {
        try {
            out.writeInt(frame.size)
            out.write(frame)
            out.flush()
        } catch (broken: IOException) {
            throw TogetherFailed(TogetherFailureReason.UNREACHABLE, broken)
        }
    }

    override suspend fun close() = shutdown()

    private fun shutdown() {
        synchronized(lock) {
            live?.let { runCatching { it.socket.close() } }
            live = null
            queued.clear()
            // Best effort, and no more than that: a JVM copies arrays wherever it likes, so this
            // wipes the one copy this object is known to hold and claims nothing else. A `send`
            // that read the key a microsecond ago can still be encoding with it, and the worst that
            // costs is one frame the friend refuses while the session is ending.
            session?.link?.key?.fill(0)
            session = null
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
        const val BACKLOG = 4
        const val LENGTH_BYTES = 4

        /** How often a quiet read comes up for air, so a cancelled session is given back at once. */
        const val POLL_MS = 250

        /** A greeting is one frame; a session queueing more than this before a socket is odd. */
        const val MAX_QUEUED = 8
    }
}
