package app.kaeru.domain.together

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A channel the test is the other end of: it delivers what it is told to, when it is told to. */
class FakeTransport : WatchTogetherTransport {
    private val _state = MutableStateFlow(ConnectionState.CLOSED)
    override val state: StateFlow<ConnectionState> = _state

    private val inbound = Channel<Result<TogetherMessage>>(Channel.UNLIMITED)

    /** Everything this side wrote, in order. */
    val sent = mutableListOf<TogetherMessage>()

    var connectedAsHost: Boolean? = null
        private set
    var connectedTo: RoomLink? = null
        private set
    var closes = 0
        private set

    /**
     * What happened to this channel, in order: every message written, `close`, and the moment the
     * flow being collected ended. Which of those comes first is the whole of what «the goodbye is
     * delivered» means, so it is recorded rather than inferred.
     */
    val order = mutableListOf<String>()

    /** While set, every write fails the way a dead socket does. */
    var sendFailure: Throwable? = null

    var endpoint: LanEndpoint? = null

    /** While set, the flow throws rather than carrying a failure — a transport with a bug in it. */
    var connectFailure: Throwable? = null

    /** The job collecting this channel, so `close` can say whether it was still alive when called. */
    private var collector: Job? = null

    override fun connect(link: RoomLink, asHost: Boolean): Flow<Result<TogetherMessage>> = flow {
        collector = currentCoroutineContext()[Job]
        connectedTo = link
        connectedAsHost = asHost
        connectFailure?.let { throw it }
        _state.value = ConnectionState.CONNECTED
        for (message in inbound) emit(message)
        _state.value = ConnectionState.CLOSED
    }.onCompletion { order += "collector-done" }

    override suspend fun send(message: TogetherMessage) {
        sendFailure?.let { throw it }
        sent += message
        order += message::class.simpleName.orEmpty().lowercase()
    }

    override suspend fun close() {
        // Which of the two came first, recorded the only way that is not a race: a job stops being
        // active the instant it is cancelled, whereas its completion handler runs whenever the
        // dispatcher gets round to it. Cancelling the collector is what runs a transport's own
        // teardown, and a real one cuts the socket and discards whatever is queued on it.
        order += if (collector?.isActive != false) "close-first" else "cancelled-first"
        order += "close"
        closes += 1
        // The channel is finished first and the call winds down afterwards, which is the shape of
        // a real graceful close: it puts a close frame on the wire and waits for the answer. The
        // suspension in the middle is what gives whoever is collecting this channel a turn to
        // notice it has ended — and a session that ends its state after this rather than before
        // will have published «connection lost» by the time it gets back here.
        _state.value = ConnectionState.CLOSED
        inbound.close()
        // More than one turn, because a real graceful close waits up to a second for the peer's
        // answer and whoever is collecting this channel gets every turn in between.
        repeat(YIELDS_WHILE_CLOSING) { yield() }
    }

    override fun hostEndpoint(): LanEndpoint? = endpoint

    /** The friend said something. */
    fun deliver(message: TogetherMessage) {
        inbound.trySend(Result.success(message))
    }

    /** A frame arrived that the transport could not make anything of. */
    fun deliver(failure: Throwable) {
        inbound.trySend(Result.failure(failure))
    }

    /** The channel is finished — reconnection ran out, or the socket will not come back. */
    fun finish() = inbound.close()

    /** Everything of one type this side sent, which is what most assertions are about. */
    inline fun <reified T : TogetherMessage> sentOf(): List<T> = sent.filterIsInstance<T>()
}

private const val YIELDS_WHILE_CLOSING = 4

/** A player that does exactly what it is told and remembers every word of it. */
class FakePlaybackPort : PlaybackPort {
    private val _state = MutableStateFlow(PortState())
    override val state: StateFlow<PortState> = _state

    private val _localActions = MutableSharedFlow<LocalAction>(extraBufferCapacity = 16)
    override val localActions: Flow<LocalAction> = _localActions

    data class Opened(val animeId: Int, val episode: Int, val translationId: Int?, val positionMs: Long)

    val seeks = mutableListOf<Long>()
    val rates = mutableListOf<Float>()
    val opened = mutableListOf<Opened>()
    var plays = 0
        private set
    var pauses = 0
        private set

    /** What the player ends up with when a voice is asked for that this device cannot get. */
    var fallbackTranslationId: Int? = null

    /** While set, seeking throws — a player that broke where nothing is waiting to hear about it. */
    var seekFailure: Throwable? = null

    /** False stands in for a Chromecast, which has no speed control. */
    override var supportsRate: Boolean = true

    /**
     * Whether an episode this fake opens is ready the moment it is opened. False stands in for
     * the seconds a real player spends resolving and reading the manifest, during which the
     * episode is named but nothing can be seeked.
     */
    var opensReady: Boolean = true

    override suspend fun play() {
        plays += 1
        _state.update { it.copy(playing = true) }
    }

    override suspend fun pause() {
        pauses += 1
        _state.update { it.copy(playing = false) }
    }

    override suspend fun seekTo(positionMs: Long) {
        seekFailure?.let { throw it }
        seeks += positionMs
        _state.update { it.copy(positionMs = positionMs) }
    }

    override suspend fun setRate(factor: Float) {
        rates += factor
    }

    /** Every time the picture was turned down or back up, in order. */
    val ducks = mutableListOf<Boolean>()

    override fun duck(on: Boolean) {
        ducks += on
    }

    override suspend fun openEpisode(animeId: Int, episode: Int, translationId: Int?, positionMs: Long) {
        opened += Opened(animeId, episode, translationId, positionMs)
        _state.update {
            it.copy(
                animeId = animeId,
                episode = episode,
                translationId = fallbackTranslationId ?: translationId,
                positionMs = positionMs,
                playing = true,
                ready = opensReady,
            )
        }
    }

    /** What the picture is doing, as the test says it is. */
    fun showing(
        animeId: Int? = 100,
        episode: Int? = 4,
        translationId: Int? = 11,
        positionMs: Long = 0,
        playing: Boolean = true,
        buffering: Boolean = false,
        ready: Boolean = true,
    ) {
        _state.value = PortState(positionMs, playing, buffering, animeId, episode, translationId, ready)
    }

    /** The manifest was read: the episode on screen can be seeked now. */
    fun ready() = _state.update { it.copy(ready = true) }

    fun moveTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }

    fun buffering(buffering: Boolean) = _state.update { it.copy(buffering = buffering) }

    /**
     * This viewer did something.
     *
     * A shared flow with buffer and no replay accepts a value with nobody subscribed and drops it,
     * so the subscriber count is what the check has to be about — otherwise a test that forgot to
     * start a session passes quietly.
     */
    fun did(action: LocalAction) {
        check(_localActions.subscriptionCount.value > 0) { "nobody was listening for $action" }
        check(_localActions.tryEmit(action)) { "$action did not fit in the buffer" }
    }
}

/** A microphone that opens instantly and produces whatever the test says it produces. */
class FakeVoiceCapture(private val clip: RecordedClip? = null) : VoiceCapture {
    val open = MutableStateFlow(false)
    var cancelled = 0
    override val recording: StateFlow<Boolean> get() = open
    override val maxDurationMs: Int get() = 30_000
    override fun start(): Boolean {
        open.value = true
        return true
    }
    override fun level(): Float = 0.5f
    override fun elapsedMs(): Int = 1_000
    override fun stop(): RecordedClip? {
        open.value = false
        return clip
    }
    override fun cancel() {
        cancelled++
        open.value = false
    }
}

/**
 * A clock that is the test's own virtual time.
 *
 * The session stamps pings and state reports with it and measures everything against it, so a
 * clock that did not move with `advanceTimeBy` would make every drift look like a stall.
 */
class VirtualClock(private val zone: ZoneId = ZoneOffset.UTC, private val millis: () -> Long) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = VirtualClock(zone, millis)
    override fun instant(): Instant = Instant.ofEpochMilli(millis())
    override fun millis(): Long = millis.invoke()
}
