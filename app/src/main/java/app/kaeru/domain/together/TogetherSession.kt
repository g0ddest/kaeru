package app.kaeru.domain.together

import app.kaeru.di.PlaybackScope
import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** A room this phone is offering, before there is a link: what listens, and the address to put in it. */
data class HostChannel(val transport: WatchTogetherTransport, val endpoint: LanEndpoint?)

/** How this device opens a room of its own, which is the one case a link cannot be routed by. */
fun interface HostTransports {
    fun open(): HostChannel
}

/**
 * Two phones watching one episode, and everything that has to be true for that to feel like
 * sitting on the same sofa.
 *
 * Symmetric by design: there is no conductor, only two people who may both pause, scrub and
 * change episode. When their commands cross on the wire the later one wins, and «later» is a
 * count that each side raises above whatever it last heard — so the two orders agree without
 * either phone having to trust the other's clock.
 *
 * Nothing here throws at a caller. A room that will not open, a friend who never comes and a
 * channel that dies are all [SessionState.Lost], because each of them is something a screen has
 * to say out loud and none of them is something a button press can handle.
 */
@Singleton
class TogetherSession @Inject constructor(
    private val transports: TransportFactory,
    private val hosting: HostTransports,
    private val port: PlaybackPort,
    private val clock: Clock,
    @param:PlaybackScope private val scope: CoroutineScope,
) : TogetherSessionApi {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Dropping the oldest when nobody is keeping up: an overlay that has fallen sixty-four
     * reactions behind has already lost the moment, and stalling the session to deliver them
     * would cost the picture rather than the backlog.
     */
    private val _events = MutableSharedFlow<TogetherEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<TogetherEvent> = _events.asSharedFlow()

    private val random = SecureRandom()
    private val offsets = ClockOffset()

    /** Everything below belongs to the session that is running, and is reset by the next one. */
    private var channel: WatchTogetherTransport? = null
    private var running: Job? = null
    private var rejoin: Job? = null
    private var becomingLive: Job? = null
    private var asHost = false
    private var myName = ""
    private var peerName = ""
    private var animeId = 0
    private var seq = 0L
    private var peerSeq = 0L
    private var lastControl = Control(0, byHost = false)
    private var eventIds = 0L
    private var peer: PeerReport? = null
    private var drift = 0L
    private var correcting = false
    private var voice: VoiceBuffer? = null
    private var hello = CompletableDeferred<TogetherMessage.Hello>()
    private var lastFailure: Throwable? = null

    // ---- opening and closing ----

    override suspend fun host(name: String): RoomLink {
        stop()
        val offering = hosting.open()
        val room = RoomLink.random(random)
        val link = offering.endpoint?.let { room.copy(lan = it) } ?: room
        begin(name, link, offering.transport, asHost = true)
        // No clock on this one. A room is open until somebody walks into it or the person who
        // made it says otherwise: a friend who reads the message a minute later is still the
        // friend it was made for, and the screen's own thirty seconds only take the line down.
        _state.value = SessionState.Hosting(link, waiting = true)
        return link
    }

    override suspend fun join(link: RoomLink, name: String) {
        stop()
        begin(name, link, transports.forLink(link), asHost = false)
        _state.value = SessionState.Joining(link, hello = null)
        // Either nobody was there or the channel said why on its way out; the second is the better
        // answer, so a failure that already landed wins over the clock.
        val greeting = withTimeoutOrNull(WAIT_TIMEOUT_MS) { hello.await() }
            ?: return lose(LostReason.WAIT_TIMEOUT)
        if (_state.value !is SessionState.Joining) return
        // The viewer decides, and the screen they decide on is the one that opens the episode —
        // this waits for it rather than starting a video behind a question nobody has answered.
        // And returns, with the one thing the join screen is made of. Going live is somebody
        // else's moment: the viewer has not said yes yet, and starting a video behind a question
        // nobody has answered is the thing this order exists to prevent.
        becomingLive = scope.launch {
            port.state.first { it.animeId == greeting.animeId && it.episode == greeting.episode }
            // Where they are now, not where they were when they said hello: a viewer reading the
            // invitation for ten seconds is ten seconds behind by the time they say yes.
            port.seekTo(peerPositionNow() ?: (greeting.positionMs + offsets.offsetMs))
            goLive(greeting.name)
            mentionVoice(greeting.translationId)
        }
    }

    /** The friend's position on this device's clock, from their last report, or null if silent. */
    private fun peerPositionNow(): Long? {
        val report = peer ?: return null
        val moved = if (report.playing) clock.millis() - report.sentAt else 0
        return report.positionMs + moved + offsets.offsetMs
    }

    override suspend fun leave() {
        val open = channel
        if (open != null) send(TogetherMessage.Bye(nextSeq()))
        // Whatever the last correction left behind is not this viewer's speed to keep.
        port.setRate(SyncPolicy.NORMAL)
        stop()
        _state.value = SessionState.Ended
    }

    /**
     * The way out of a wait, which is not the same as the way out of a session.
     *
     * A host who stops watching the door has not closed it: the room stays open and a friend who
     * arrives later still walks in. A guest who says no is done — there is nothing to keep.
     */
    override fun watchAlone() {
        when (val now = _state.value) {
            is SessionState.Hosting -> _state.value = now.copy(waiting = false)
            is SessionState.Joining, is SessionState.Lost -> scope.launch {
                port.setRate(SyncPolicy.NORMAL)
                stop()
                _state.value = SessionState.Idle
            }
            else -> Unit
        }
    }

    /** Wires one session up: the channel, the four clocks running on it, and this viewer's actions. */
    private fun begin(name: String, link: RoomLink, transport: WatchTogetherTransport, asHost: Boolean) {
        myName = name
        peerName = ""
        this.asHost = asHost
        channel = transport
        seq = 0
        peerSeq = 0
        lastControl = Control(0, byHost = false)
        peer = null
        drift = 0
        correcting = false
        voice = null
        lastFailure = null
        hello = CompletableDeferred()
        animeId = port.state.value.animeId ?: 0
        running = scope.launch {
            launch { greetOnConnect(transport) }
            launch { pings() }
            launch { reports() }
            launch { corrections() }
            launch { port.localActions.collect { forward(it) } }
            transport.connect(link, asHost).collect { frame ->
                frame.onSuccess { receive(it) }.onFailure { lastFailure = it }
            }
            // Only a channel that is finished for good gets here: a frame that would not decode
            // is a value inside the flow, not the end of it.
            lose(reasonOf(lastFailure))
        }
    }

    /** Ends whatever was running, quietly. Nothing after this belongs to the session that was. */
    private suspend fun stop() {
        rejoin?.cancel()
        becomingLive?.cancel()
        running?.cancel()
        rejoin = null
        becomingLive = null
        running = null
        val open = channel ?: return
        channel = null
        runCatching { open.close() }
    }

    /**
     * The first explanation is the true one.
     *
     * Giving up on a wait closes the channel, and a closed channel arrives here a moment later as
     * a lost connection — which is the consequence, not the reason, and «связь потеряна» over a
     * friend who simply never came is the wrong thing to have on screen.
     */
    private suspend fun lose(reason: LostReason, close: Boolean = true) {
        val now = _state.value
        if (now is SessionState.Ended || now is SessionState.Idle || now is SessionState.Lost) return
        _state.value = SessionState.Lost(reason)
        rejoin?.cancel()
        becomingLive?.cancel()
        if (!close) return
        val open = channel ?: return
        channel = null
        // Closed here and not in a coroutine of its own: the transports are one per process, so a
        // close left in flight would land on whatever session opened next.
        runCatching { open.close() }
    }

    /**
     * What a dead channel is to a viewer. A relay that was never configured and a room with two
     * people already in it are answers rather than accidents, and both read as nonsense if they
     * are shown as «связь потеряна».
     */
    private fun reasonOf(failure: Throwable?): LostReason = when {
        failure is RelayNotConfigured -> LostReason.NOT_CONFIGURED
        failure is TogetherFailed && failure.reason == TogetherFailureReason.ROOM_FULL -> LostReason.ROOM_FULL
        else -> LostReason.CONNECTION
    }

    // ---- the four things that happen on a clock ----

    /**
     * The guest says hello; the host answers one. A relay room has nobody in it when the phone
     * that made it arrives, so a host greeting an empty room would be greeting the wall — the
     * friend's own hello is the first evidence there is anybody to talk to.
     */
    private suspend fun greetOnConnect(transport: WatchTogetherTransport) {
        var connected = false
        transport.state.collect { state ->
            val up = state == ConnectionState.CONNECTED
            // The guest only. A host's channel comes up the moment the guest's first frame
            // decrypts, by a different route than that frame itself, and nothing orders the two —
            // so a host writing here would send a ping or its hello depending on the weather. A
            // host answers where the answer belongs, on the inbound path.
            if (up && !connected && !asHost) {
                // Before anything else this side can possibly send: a host holds the slot open
                // only until a first frame decrypts, and a ping arriving first is a frame that
                // says nothing about who sent it.
                send(greeting())
                send(TogetherMessage.Ping(clock.millis(), nextSeq()))
            }
            connected = up
        }
    }

    private suspend fun pings() {
        while (channel != null) {
            delay(PING_INTERVAL_MS)
            send(TogetherMessage.Ping(clock.millis(), nextSeq()))
        }
    }

    private suspend fun reports() {
        while (channel != null) {
            delay(STATE_INTERVAL_MS)
            if (_state.value !is SessionState.Live) continue
            val now = port.state.value
            send(TogetherMessage.State(now.positionMs, now.playing, now.buffering, clock.millis(), nextSeq()))
            forgetStaleVoice()
        }
    }

    private suspend fun corrections() {
        while (channel != null) {
            delay(SYNC_INTERVAL_MS)
            correct()
        }
    }

    /**
     * One look at the gap, and usually nothing to do about it.
     *
     * The friend's last report is extrapolated to now before it is judged, because a report that
     * is a second and a half old describes where they were, not where they are — and half a
     * second is the whole width of the band that means «leave it alone».
     */
    private suspend fun correct() {
        if (_state.value !is SessionState.Live) return
        val report = peer ?: return
        val now = clock.millis()
        if (now - report.at > STALE_STATE_MS) return
        val here = port.state.value
        // Their clock is in `sentAt` and ours is in `now`; the difference between the two clocks
        // is exactly what the policy's offset undoes, so the two mix here on purpose.
        val there = if (report.playing) report.positionMs + (now - report.sentAt) else report.positionMs
        val action = SyncPolicy.decide(
            localMs = here.positionMs,
            remoteMs = there,
            remotePlaying = report.playing,
            localPlaying = here.playing,
            offsetMs = offsets.offsetMs,
            correcting = correcting,
        )
        when (action) {
            SyncAction.None -> Unit
            is SyncAction.Rate -> {
                port.setRate(action.factor)
                correcting = action.factor != SyncPolicy.NORMAL
            }
            is SyncAction.SeekTo -> {
                normalSpeed()
                port.seekTo(action.positionMs)
            }
            is SyncAction.SeekAndNotify -> {
                normalSpeed()
                port.seekTo(action.positionMs)
                announce(TogetherEvent.Notice(NoticeKind.CATCHING_UP, peerName, positionMs = action.positionMs))
            }
        }
    }

    private suspend fun normalSpeed() {
        if (!correcting) return
        port.setRate(SyncPolicy.NORMAL)
        correcting = false
    }

    // ---- what the friend said ----

    private suspend fun receive(message: TogetherMessage) {
        if (message !is TogetherMessage.PeerLeft) {
            // Replay protection, and it belongs here rather than in a transport: a relay is
            // outside the trust boundary and can hand the same encrypted frame over twice, which
            // would be an old seek, an old episode or somebody's voice clip played again. The
            // peer's count only ever rises, so anything not above the highest already accepted
            // from them did not come from them now.
            if (message.seq <= peerSeq) return
            peerSeq = message.seq
            // And a Lamport count of our own on top of it: raising ours above anything we hear is
            // what makes «later» mean the same thing on both phones, so neither side can be
            // outvoted for ever merely by being the quieter one.
            seq = maxOf(seq, message.seq)
        }
        when (message) {
            is TogetherMessage.Hello -> arrived(message)
            is TogetherMessage.Play -> control(message.seq) {
                catchUpTo(message.positionMs)
                port.play()
                announce(TogetherEvent.Notice(NoticeKind.PLAYED, peerName, positionMs = message.positionMs))
            }
            is TogetherMessage.Pause -> control(message.seq) {
                catchUpTo(message.positionMs)
                port.pause()
                announce(TogetherEvent.Notice(NoticeKind.PAUSED, peerName, positionMs = message.positionMs))
            }
            is TogetherMessage.Seek -> control(message.seq) {
                port.seekTo(message.positionMs)
                announce(TogetherEvent.Notice(NoticeKind.SEEKED, peerName, positionMs = message.positionMs))
            }
            is TogetherMessage.Episode -> control(message.seq) { changed(message) }
            is TogetherMessage.State -> reported(message)
            is TogetherMessage.Chat -> announce(
                TogetherEvent.ChatItem(nextEventId(), fromPeer = true, text = message.text, at = clock.millis()),
            )
            is TogetherMessage.Reaction -> announce(
                TogetherEvent.ReactionEvent(nextEventId(), fromPeer = true, kind = message.kind),
            )
            is TogetherMessage.Voice -> collect(message)
            is TogetherMessage.Ping -> send(
                TogetherMessage.Pong(message.sentAt, clock.millis(), clock.millis(), nextSeq()),
            )
            is TogetherMessage.Pong -> {
                offsets.record(message.pingSentAt, message.receivedAt, message.sentAt, clock.millis())
                republishLive()
            }
            is TogetherMessage.Bye, is TogetherMessage.PeerLeft -> departed()
        }
    }

    /**
     * Whether this action came after everything already applied, and what to do about it if so.
     *
     * Ties go to the host — not because a host is in charge, but because both phones agree on
     * which one it is, so both settle a photo finish the same way.
     */
    private suspend fun control(seq: Long, apply: suspend () -> Unit) {
        // Not before this side has joined. A host is live the moment it answers a hello, while a
        // guest is still on its join screen with nothing started for the session — and a pause or
        // an episode change applied there would drive whatever that viewer happened to be
        // watching. The outbound side has always had this guard; this is the same rule read the
        // other way.
        if (_state.value !is SessionState.Live) return
        val theirs = Control(seq, byHost = !asHost)
        if (theirs <= lastControl) return
        lastControl = theirs
        apply()
    }

    /** A jump small enough to be read as the video stuttering is not worth making. */
    private suspend fun catchUpTo(positionMs: Long) {
        if (kotlin.math.abs(port.state.value.positionMs - positionMs) < SyncPolicy.IGNORE_MS) return
        port.seekTo(positionMs)
    }

    private suspend fun arrived(message: TogetherMessage.Hello) {
        peerName = message.name
        if (animeId == 0) animeId = message.animeId
        rejoin?.cancel()
        rejoin = null
        if (asHost) {
            // The answer first, and only then the round trip the clocks need — for the same
            // reason the guest leads with one, and so a relay host never pings a room it has
            // deliberately not greeted.
            send(greeting())
            send(TogetherMessage.Ping(clock.millis(), nextSeq()))
            goLive(message.name)
            return
        }
        // The guest's own join is still suspended on this; everything it does next happens there,
        // where it can be waited on.
        if (!hello.isCompleted) hello.complete(message)
        if (_state.value is SessionState.Joining) {
            _state.value = SessionState.Joining(
                link = (_state.value as SessionState.Joining).link,
                hello = PeerHello(
                    message.name, message.animeId, message.episode, message.translationId,
                    message.positionMs, message.playing,
                ),
            )
        } else {
            goLive(message.name)
        }
    }

    /**
     * The friend moved to another episode, unless this phone is already on it — which is what a
     * guest's own opening looks like coming back the other way, and restarting somebody's episode
     * from zero to tell them what they are watching is worse than saying nothing.
     */
    private suspend fun changed(message: TogetherMessage.Episode) {
        val here = port.state.value
        if (here.episode == message.episode && here.translationId == message.translationId) return
        port.openEpisode(animeId, message.episode, message.translationId, 0)
        announce(TogetherEvent.Notice(NoticeKind.EPISODE, peerName, episode = message.episode))
        mentionVoice(message.translationId)
    }

    private fun reported(message: TogetherMessage.State) {
        peer = PeerReport(message.positionMs, message.playing, message.sentAt, clock.millis())
        drift = port.state.value.positionMs - (message.positionMs + offsets.offsetMs)
        republishLive()
    }

    /**
     * The friend's socket went away. It is not the end: a room keeps the seat for half a minute,
     * which is about how long a train takes to leave a tunnel.
     */
    private suspend fun departed() {
        if (peerName.isNotEmpty()) announce(TogetherEvent.Notice(NoticeKind.LEFT, peerName))
        // Their counter starts again when they do, so the replay guard has to let go of the
        // high-water mark it built up — otherwise the hello of a friend walking back in, carrying
        // a 1 against a mark in the tens, is dropped as a replay and the window can never be
        // used. The same for the last action applied: a returned peer must not have to count its
        // way back up before it is allowed to pause anything.
        peerSeq = 0
        lastControl = Control(0, byHost = false)
        rejoin?.cancel()
        rejoin = scope.launch {
            delay(REJOIN_WINDOW_MS)
            lose(LostReason.CONNECTION)
        }
    }

    private fun goLive(name: String) {
        val already = _state.value is SessionState.Live
        _state.value = SessionState.Live(name, offsets.offsetMs, drift)
        if (!already) announce(TogetherEvent.Notice(NoticeKind.JOINED, name))
    }

    private fun republishLive() {
        val live = _state.value as? SessionState.Live ?: return
        _state.value = live.copy(offsetMs = offsets.offsetMs, driftMs = drift)
    }

    /** «У тебя другая озвучка»: said only when the player actually ended up somewhere else. */
    private fun mentionVoice(wanted: Int?) {
        if (wanted == null) return
        if (port.state.value.translationId == wanted) return
        announce(TogetherEvent.Notice(NoticeKind.OTHER_VOICE, peerName))
    }

    // ---- what this viewer did ----

    private suspend fun forward(action: LocalAction) {
        // Only while there is somebody to tell. A guest opening the episode they are joining is
        // this viewer's own action by every measure, and forwarding it would ask the friend to
        // reopen the episode they are already watching.
        if (channel == null || _state.value !is SessionState.Live) return
        val message = when (action) {
            is LocalAction.Play -> TogetherMessage.Play(action.positionMs, nextSeq())
            is LocalAction.Pause -> TogetherMessage.Pause(action.positionMs, nextSeq())
            is LocalAction.Seek -> TogetherMessage.Seek(action.positionMs, nextSeq())
            is LocalAction.Episode -> {
                animeId = action.animeId
                TogetherMessage.Episode(action.episode, action.translationId, nextSeq())
            }
        }
        lastControl = Control(message.seq, byHost = asHost)
        send(message)
    }

    // ---- talking ----

    override suspend fun sendChat(text: String) {
        if (channel == null) return
        val line = text.take(TogetherMessage.MAX_CHAT_CHARS)
        send(TogetherMessage.Chat(line, nextSeq()))
        announce(TogetherEvent.ChatItem(nextEventId(), fromPeer = false, text = line, at = clock.millis()))
    }

    override suspend fun sendReaction(kind: ReactionKind) {
        if (channel == null) return
        send(TogetherMessage.Reaction(kind, nextSeq()))
        announce(TogetherEvent.ReactionEvent(nextEventId(), fromPeer = false, kind = kind))
    }

    override suspend fun sendVoice(bytes: ByteArray, durationMs: Int) {
        if (channel == null || bytes.isEmpty()) return
        val cut = TogetherMessage.MAX_VOICE_CHUNK_BYTES
        val total = (bytes.size + cut - 1) / cut
        for (index in 0 until total) {
            val slice = bytes.copyOfRange(index * cut, minOf((index + 1) * cut, bytes.size))
            send(TogetherMessage.Voice(index, total, slice, durationMs.toLong(), nextSeq()))
        }
        announce(TogetherEvent.VoiceClip(nextEventId(), fromPeer = false, bytes = bytes, durationMs = durationMs))
    }

    /**
     * A clip arrives in order or not at all.
     *
     * Both transports deliver in order, so a slice that is not the one expected means the clip
     * before it is already lost — and half a voice message is worse than none, because it plays
     * as somebody being cut off.
     */
    private fun collect(message: TogetherMessage.Voice) {
        forgetStaleVoice()
        val holding = voice
        if (message.chunk == 0) {
            voice = VoiceBuffer(message.total, message.durationMs, clock.millis()).also {
                it.parts += message.bytes
            }
        } else if (holding == null || holding.total != message.total || holding.parts.size != message.chunk) {
            voice = null
            return
        } else {
            holding.parts += message.bytes
        }
        val clip = voice ?: return
        if (clip.parts.size < clip.total) return
        voice = null
        announce(
            TogetherEvent.VoiceClip(
                id = nextEventId(),
                fromPeer = true,
                bytes = clip.parts.fold(ByteArray(0)) { all, part -> all + part },
                durationMs = clip.durationMs.toInt(),
            ),
        )
    }

    private fun forgetStaleVoice() {
        val holding = voice ?: return
        if (clock.millis() - holding.startedAt > VOICE_TIMEOUT_MS) voice = null
    }

    // ---- plumbing ----

    private fun greeting(): TogetherMessage.Hello {
        val now = port.state.value
        return TogetherMessage.Hello(
            name = myName,
            animeId = now.animeId ?: animeId,
            episode = now.episode ?: 1,
            translationId = now.translationId,
            positionMs = now.positionMs,
            playing = now.playing,
            seq = nextSeq(),
        )
    }

    /**
     * A write that fails is a dropped action, never a dropped session: whether the channel is
     * still there is [WatchTogetherTransport.state]'s to say, and the protocol's own rule is that
     * the next action supersedes this one anyway.
     */
    private suspend fun send(message: TogetherMessage) {
        val open = channel ?: return
        try {
            open.send(message)
        } catch (dropped: TogetherFailed) {
            lastFailure = dropped
        }
    }

    private fun announce(event: TogetherEvent) {
        _events.tryEmit(event)
    }

    private fun nextSeq(): Long = ++seq

    private fun nextEventId(): Long = ++eventIds

    /** Whose action, and when — the whole of «the last one wins». */
    private data class Control(val seq: Long, val byHost: Boolean) : Comparable<Control> {
        override fun compareTo(other: Control): Int =
            compareValuesBy(this, other, { it.seq }, { it.byHost })
    }

    /** The friend's last word about where they are, and when this side heard it. */
    private data class PeerReport(
        val positionMs: Long,
        val playing: Boolean,
        val sentAt: Long,
        val at: Long,
    )

    private class VoiceBuffer(val total: Int, val durationMs: Long, val startedAt: Long) {
        val parts = mutableListOf<ByteArray>()
    }

    companion object {
        /** Every wait has the same half a minute, and the same way out of it. */
        const val WAIT_TIMEOUT_MS = 30_000L

        /** How long a friend whose socket went away has to walk back into the room. */
        const val REJOIN_WINDOW_MS = 30_000L

        /** One round trip for the clocks, and what keeps a quiet LAN socket from idling out. */
        const val PING_INTERVAL_MS = 5_000L

        /** Where this side is, often enough for the other to measure drift against. */
        const val STATE_INTERVAL_MS = 1_000L

        /** How often the gap is looked at. Twice the report interval, so it is never acting blind. */
        const val SYNC_INTERVAL_MS = 2_000L

        /** Past this, the friend's last report is too old to extrapolate from. */
        const val STALE_STATE_MS = 5_000L

        /** A clip nobody finished sending is not worth holding on to. */
        const val VOICE_TIMEOUT_MS = 30_000L

        private const val EVENT_BUFFER = 64
    }
}
