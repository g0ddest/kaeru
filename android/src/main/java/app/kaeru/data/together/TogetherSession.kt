package app.kaeru.data.together

import android.util.Log
import app.kaeru.domain.error.RelayNotConfigured
import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import app.kaeru.domain.together.ClockOffset
import app.kaeru.domain.together.ConnectionState
import app.kaeru.domain.together.LanEndpoint
import app.kaeru.domain.together.LocalAction
import app.kaeru.domain.together.LostReason
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.PeerHello
import app.kaeru.domain.together.PlaybackPort
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.SyncAction
import app.kaeru.domain.together.SyncPolicy
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherMessage
import app.kaeru.domain.together.TogetherSessionApi
import app.kaeru.domain.together.TransportFactory
import app.kaeru.domain.together.WatchTogetherTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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

/** A room this phone is offering, before there is a link: what listens, and the address to put in it. */
data class HostChannel(val transport: WatchTogetherTransport, val endpoint: LanEndpoint?)

/** How this device opens a room of its own, which is the one case a link cannot be routed by. */
fun interface HostTransports {
    /**
     * Suspending because opening a room enumerates this device's network interfaces and binds a
     * socket, and the only caller is a button press on the main thread.
     */
    suspend fun open(): HostChannel
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
class TogetherSession(
    private val transports: TransportFactory,
    private val hosting: HostTransports,
    private val port: PlaybackPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TogetherSessionApi {

    /**
     * The backstop for anything that throws where nobody is waiting to catch it.
     *
     * The scope this runs on is the player's, shared with the thing decoding video, and it has no
     * handler of its own — so an unexpected throwable in a ticker or in the job that waits for a
     * viewer to open an episode would go to Android's default handler and take the app down in
     * the middle of somebody's film. A shared viewing failing is worth a state and a line in the
     * log; it is not worth the process.
     */
    private val failures = CoroutineExceptionHandler { _, broken ->
        Log.w(TAG, "The shared viewing failed unexpectedly", broken)
        scope.launch { runCatching { lose(LostReason.CONNECTION) } }
    }

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

    /** Whether a friend who has just gone is still owed the one hello that may count below the mark. */
    private var rejoining = false

    /** The five loops and collectors of one session, so losing it stops all of them at once. */
    private var ticks: Job? = null
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

    /** The factor the player was last asked to run at, so the same one is not asked for twice. */
    private var currentRate = SyncPolicy.NORMAL

    /** The friend's player said it is filling its buffer. */
    private var peerLoading = false

    /** This side paused itself to wait for them, and starts again when they are ready. */
    private var heldForPeer = false

    /** When waiting stops being kindness and becomes a frozen picture nobody explained. */
    private var holdUntil = 0L

    /** Nothing is corrected before this instant — the quiet after a jump. */
    private var correctionSettledAt = 0L
    private var voice: VoiceBuffer? = null

    /**
     * The friend's latest episode change while this side is still on its join screen.
     *
     * Kept rather than applied: the port is nobody's to drive until the viewer has said yes. Kept
     * rather than dropped: a host is live from the moment it answers a hello, a guest may read the
     * invitation for a minute, and autoplay running into the next episode in that window is sent
     * once and never repeated. Dropping it puts the two phones on different episodes with a
     * corrector comparing positions across them.
     */
    private var pendingEpisode: TogetherMessage.Episode? = null

    /**
     * Whether that change is still the last thing the friend said about where they are.
     *
     * A report carries a position and no episode, so one that arrived before the change
     * describes the episode before it. Order rather than a timestamp: the two can land in the
     * same millisecond, and which came first is the whole of the question.
     */
    private var movedSinceReport = false
    private var hello = CompletableDeferred<TogetherMessage.Hello>()

    /** When the friend's hello arrived, on this clock, for carrying its position forward. */
    private var helloAt = 0L

    /**
     * Whether the friend's next report should be looked at the moment it arrives.
     *
     * Set by a guest on going live, so the first correction happens as soon as there is something
     * to correct against rather than up to two seconds later on the next tick — the seconds spent
     * reading the invitation are closed on the first report, not the second.
     */
    private var syncOnReport = false

    /**
     * Why the channel is ending, as the channel itself said it — set once and never overwritten.
     *
     * A write failing is not an answer to that question. A relay closing a room arrives as
     * exactly this: a reason on the way in, and a socket that has stopped accepting writes a
     * moment later, and letting the second one speak turns «в комнате уже двое» into «связь
     * потеряна».
     */
    private var closingBecause: Throwable? = null

    /** Undecodable frames in a row. A key that does not match never starts matching. */
    private var refused = 0

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
        becomingLive = scope.launch(failures) {
            // Any episode this side has been told about, because the screen opens the one named in
            // the hello and the friend may have moved on since. Waiting only for where they are
            // now would be waiting for something nobody is going to open.
            //
            // And ready, not merely named. The player names the episode the moment it is told to
            // open it and then spends seconds resolving the stream and reading the manifest; a
            // seek made inside that window lands on an empty player and is written over by the
            // position the episode is then prepared at — which is this viewer's own, not the
            // friend's. The same goes for a pause, which the start itself would undo.
            //
            // Or failed, which is the other end of the same wait: a stream that would not
            // resolve is never going to be ready, and a guest left waiting for it sits on a join
            // screen over the player's own «Повторить» until the thirty seconds run out and the
            // session is gone. Live on a broken picture keeps the retry inside the session.
            var settled = port.state.first {
                (it.ready || it.failed) && it.animeId == greeting.animeId &&
                    (it.episode == greeting.episode || it.episode == pendingEpisode?.episode)
            }
            // And then, if they did move on, following them there before going live rather than
            // after — two phones on different episodes is the thing this is all for. Usually
            // nothing to do: the screen was told which episode to open and opened that one.
            val moved = pendingEpisode
            if (moved != null) {
                pendingEpisode = null
                lastControl = Control(moved.seq, byHost = !asHost)
                changed(moved)
                // Opening returns before the manifest is read: the same wait, for the same reason.
                settled = port.state.first {
                    (it.ready || it.failed) && it.animeId == greeting.animeId && it.episode == moved.episode
                }
            }
            if (settled.ready) {
                // Where they are now, not where they were when they said hello: a viewer reading
                // the invitation for ten seconds is ten seconds behind by the time they say yes.
                port.seekTo(reportedPositionNow() ?: (greeting.positionMs + offsets.offsetMs))
                // A friend who is paused is not an invitation to start playing at them — including
                // one who paused after saying hello: their pause cannot reach a join screen, but
                // their reports say so all the same.
                if (!(peer?.playing ?: greeting.playing)) port.pause()
            }
            goLive(greeting.name)
            // One look at the gap now rather than on the next tick, and another at the friend's
            // first report.
            correct()
            syncOnReport = true
            if (moved == null) mentionVoice(greeting.translationId)
            // And one more when the picture actually starts moving, which is the only one of the
            // three that is reliably worth anything: the policy refuses to judge a picture that is
            // not playing, and a guest spends the seconds after the seek buffering HLS segments.
            // Without this the first real correction is whichever two-second tick comes next.
            //
            // Last in this coroutine on purpose: it suspends until the episode starts, and the
            // job is cancelled with the session, so nothing is left waiting for a picture that
            // is never going to play.
            port.state.first { it.playing }
            correct()
        }
    }

    /**
     * The friend changed episode while this side is still on its join screen.
     *
     * Kept rather than applied — the port is nobody's to drive until the viewer has said yes —
     * and said out loud on the state, because the screen that opens the episode has to open this
     * one rather than the one the hello named.
     */
    private fun stash(message: TogetherMessage.Episode) {
        pendingEpisode = message
        movedSinceReport = true
        val joining = _state.value as? SessionState.Joining ?: return
        _state.value = joining.copy(pendingEpisode = message.episode)
    }

    /** The friend's position on this device's clock, from their last report, or null if silent. */
    private fun reportedPositionNow(): Long? {
        val report = peer ?: return null
        val moved = if (report.playing) clock.millis() - report.sentAt else 0
        return report.positionMs + moved + offsets.offsetMs
    }

    override fun peerPositionNow(): Long? {
        // An episode they moved to and have not reported from yet: they are at the start of it,
        // and both the last report and the hello describe the episode before. Zero rather than
        // nothing, because nothing would fall back to exactly that stale hello.
        if (pendingEpisode != null && movedSinceReport) return 0
        reportedPositionNow()?.let { return it }
        // Nothing reported yet: the hello, carried forward by the time spent reading it. Bounded,
        // because a position past the end of the episode would open the player on its last frame
        // and start the countdown to the next one.
        val greeting = (_state.value as? SessionState.Joining)?.hello ?: return null
        val since = if (greeting.playing) (clock.millis() - helloAt).coerceIn(0, HELLO_CARRY_MAX_MS) else 0
        return greeting.positionMs + since
    }

    override suspend fun leave() {
        // Nothing to leave twice. Re-assigning the state it is already holding emits nothing —
        // a StateFlow conflates an equal value — so a screen that redraws itself from that state
        // would be left holding whatever it drew the first time.
        val settled = _state.value
        if (settled is SessionState.Ended || settled is SessionState.Idle) return
        // On this session's own scope rather than the caller's, and only joined from there. The
        // last place this is called from is a player screen being destroyed, whose scope is
        // cancelled moments later; a goodbye dropped on the way out is a friend waiting half a
        // minute to be told what already happened.
        scope.launch(failures) {
            val open = channel
            if (open != null) send(TogetherMessage.Bye(nextSeq()))
            // Whatever the last correction left behind is not this viewer's speed to keep.
            forceNormalSpeed()
            // Settled before the channel comes down, as a friend's goodbye already is. Closing the
            // channel ends the flow being collected, and the collector's own next line is the one
            // that calls a finished channel a lost connection — so a leave done the other way
            // round puts «связь с другом потеряна» on screen on its way out of a session the
            // viewer chose to end.
            _state.value = SessionState.Ended
            stop()
        }.join()
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
            is SessionState.Joining, is SessionState.Lost -> scope.launch(failures) {
                forceNormalSpeed()
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
        rejoining = false
        pendingEpisode = null
        movedSinceReport = false
        closingBecause = null
        refused = 0
        hello = CompletableDeferred()
        helloAt = 0
        syncOnReport = false
        animeId = port.state.value.animeId ?: 0
        running = scope.launch(failures) {
            try {
                ticks = launch {
                    launch { greetOnConnect(transport) }
                    launch { pings() }
                    launch { greetings() }
                    launch { reports() }
                    launch { corrections() }
                    launch { port.localActions.collect { forward(it) } }
                }
                transport.connect(link, asHost).collect { frame ->
                    frame.onSuccess {
                        refused = 0
                        receive(it)
                    }.onFailure { failure ->
                        closingBecause = closingBecause ?: failure
                        garbled(failure)
                    }
                }
                // Only a channel that is finished for good gets here: a frame that would not
                // decode is a value inside the flow, not the end of it.
                lose(reasonOf(closingBecause))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: Throwable) {
                Log.w(TAG, "The channel failed", broken)
                lose(reasonOf(broken))
            }
        }
    }

    /** Ends whatever was running, quietly. Nothing after this belongs to the session that was. */
    private suspend fun stop() {
        rejoin?.cancel()
        becomingLive?.cancel()
        // The tickers first, so nothing writes another state report into a channel being shut.
        ticks?.cancel()
        // Then the channel, and only then the collector reading it. Cancelling the collector runs
        // the transport's own teardown, which cuts a socket rather than closing it — and a
        // goodbye that is still in the write queue goes with it. That is the whole of why the
        // graceful close exists, and doing this the other way round never reaches it.
        val open = channel
        channel = null
        if (open != null) runCatching { open.close() }
        running?.cancel()
        rejoin = null
        becomingLive = null
        ticks = null
        running = null
    }

    /**
     * The first explanation is the true one.
     *
     * Giving up on a wait closes the channel, and a closed channel arrives here a moment later as
     * a lost connection — which is the consequence, not the reason, and «связь потеряна» over a
     * friend who simply never came is the wrong thing to have on screen.
     */
    private suspend fun lose(reason: LostReason) {
        val now = _state.value
        if (now is SessionState.Ended || now is SessionState.Idle || now is SessionState.Lost) return
        // Before anything else. A correction in force when the channel died would otherwise play
        // the rest of somebody's episode three percent slow for ever: there is no speed control
        // anywhere in this app, and nothing else ever writes the rate back.
        normalSpeed()
        _state.value = SessionState.Lost(reason)
        rejoin?.cancel()
        becomingLive?.cancel()
        // Two of these collect flows that never end on their own — the channel's state and this
        // viewer's actions — and a lost session has no use for either.
        ticks?.cancel()
        val open = channel ?: return
        channel = null
        // Closed here and not in a coroutine of its own: the transports are one per process, so a
        // close left in flight would land on whatever session opened next.
        runCatching { open.close() }
    }

    /**
     * A frame that would not decode, and what a run of them means.
     *
     * One is nothing: a corrupted packet is not a reason to end somebody's film. Three in a row
     * from a peer that is sitting there is a key that does not match — a link truncated by a chat
     * application, most often — and the alternative to saying so is a host waiting for a friend
     * who is already connected and shouting through the wrong door, in silence, for ever.
     */
    private suspend fun garbled(failure: Throwable) {
        refused += 1
        if (refused < GARBLED_LIMIT) return
        Log.w(TAG, "$refused frames in a row would not decode; giving up on this room", failure)
        lose(LostReason.CONNECTION)
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

    /**
     * A greeting is said again until somebody answers it.
     *
     * One frame, sent once, through a relay that keeps nothing: whoever is in the room first says
     * hello to an empty room, and whoever arrives second never hears it. The host answering a
     * guest's hello covers the usual order — but not a socket that blinked and redialled, which
     * has already spent the other side's only greeting. Both phones then sit in one room saying
     * nothing, which is what «Подключаемся…» for ever looked like.
     */
    private suspend fun greetings() {
        while (channel != null) {
            delay(HELLO_RETRY_MS)
            if (peerName != null) continue
            send(greeting())
        }
    }

    private suspend fun reports() {
        while (channel != null) {
            delay(STATE_INTERVAL_MS)
            // Before the guard, not after it: half a clip is thirty seconds' worth of memory
            // whether or not this side has finished joining.
            forgetStaleVoice()
            if (_state.value !is SessionState.Live) continue
            val now = port.state.value
            send(TogetherMessage.State(now.positionMs, now.playing, now.buffering, clock.millis(), nextSeq()))
        }
    }

    private suspend fun corrections() {
        while (channel != null) {
            delay(SYNC_INTERVAL_MS)
            if (heldForPeer && holdUntil > 0 && clock.millis() >= holdUntil) releaseHold()
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
        // One side follows and the other is the reference — the way every watch-together that
        // works does it. Two phones each correcting towards the other, by two different estimates
        // of the clock offset, settle a second apart and take turns jumping; the side that made
        // the room is the one the picture is measured against.
        if (asHost) {
            if (correcting) forceNormalSpeed()
            return
        }
        // Nobody is corrected while either player is filling its buffer, and nothing is corrected
        // in the seconds right after a jump: a seek costs an HLS player a stall, and a rule that
        // judges the stall it caused will order another jump. That is the loop.
        if (peerLoading || heldForPeer) return
        if (port.state.value.buffering) return
        if (clock.millis() < correctionSettledAt) return
        // A correction the player can no longer honour is one nothing will ever take off again:
        // casting started mid-nudge, every later `setRate` reaches a receiver that ignores it, and
        // the three percent stays on the engine this phone will use next.
        if (correcting && !port.supportsRate) forceNormalSpeed()
        val report = peer ?: return
        val now = clock.millis()
        if (now - report.at > STALE_STATE_MS) return
        val here = port.state.value
        // Their clock is in `sentAt` and ours is in `now`; the difference between the two clocks
        // is exactly what the policy's offset undoes, so the two mix here on purpose.
        val there = if (report.playing) report.positionMs + (now - report.sentAt) else report.positionMs
        val target = there + offsets.offsetMs
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
            is SyncAction.Rate -> if (port.supportsRate) {
                // Once. The guest now judges the gap the moment a report lands as well as on its
                // own beat, and a factor the player is already running at is not news to it.
                if (action.factor != currentRate) port.setRate(action.factor)
                currentRate = action.factor
                correcting = action.factor != SyncPolicy.NORMAL
            } else if (kotlin.math.abs(here.positionMs - target) > RATELESS_SEEK_MS) {
                // Nothing here can play slightly slow — the picture is on a television — so the
                // band that would have been nudged shut has to be jumped instead. Not at the
                // bottom of it: below a second a jump is worse than the gap it closes.
                correcting = false
                port.seekTo(target)
            }
            is SyncAction.SeekTo -> {
                normalSpeed()
                port.seekTo(action.positionMs)
                settleAfterSeek()
            }
            is SyncAction.SeekAndNotify -> {
                normalSpeed()
                port.seekTo(action.positionMs)
                announce(TogetherEvent.Notice(NoticeKind.CATCHING_UP, peerName, positionMs = action.positionMs))
                settleAfterSeek()
            }
        }
    }

    /** Ends a correction that is running. Nothing to do when none is. */
    private suspend fun normalSpeed() {
        if (!correcting) return
        forceNormalSpeed()
    }

    /**
     * Normal speed whether or not this session believes it set it.
     *
     * For the two endings a viewer asked for. `correcting` is one session's memory of one
     * correction, and a session that is being put down is the wrong place to trust it.
     */
    private suspend fun forceNormalSpeed() {
        port.setRate(SyncPolicy.NORMAL)
        currentRate = SyncPolicy.NORMAL
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
            //
            // The one exception is a hello inside the rejoin window, and only a hello: a friend
            // walking back in has restarted their count, so theirs is below the mark by
            // definition. Everything else stays gated by the old mark until that hello arrives,
            // or the window would be thirty seconds in which any captured frame plays again.
            // Once, and only while the window is open. A relay cannot forge or read a frame, but
            // it can hand one back — and an exception that stayed open for the whole window would
            // let a replayed hello re-seed the guard and the captured session follow it in order.
            val returning = rejoining && message is TogetherMessage.Hello
            if (!returning && message.seq <= peerSeq) return
            // Their count is theirs again from here, and so is the last action anybody applied —
            // a returned peer must not have to count its way back up before it may pause anything.
            if (returning) {
                rejoining = false
                lastControl = Control(0, byHost = false)
            }
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
            // Stashed rather than refused while joining; the latest one wins, as it would have
            // if it had been applied.
            is TogetherMessage.Episode ->
                if (_state.value is SessionState.Joining) stash(message)
                else control(message.seq) { changed(message) }
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
            is TogetherMessage.Bye -> departed(deliberate = true)
            is TogetherMessage.PeerLeft -> departed(deliberate = false)
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
        // A guest is about to open what the friend is watching, whatever this phone had on: the
        // hello's title is the session's from here, or following the friend to their next episode
        // would open it under the title this phone was on before.
        if (animeId == 0 || !asHost) animeId = message.animeId
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
        if (!hello.isCompleted) {
            helloAt = clock.millis()
            hello.complete(message)
        }
        val joining = _state.value
        if (joining is SessionState.Joining) {
            // Copied rather than rebuilt: an episode change that arrived before the hello is
            // already on this state, and the screen still has to be told to open that one.
            _state.value = joining.copy(
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
        // media3 keeps a playback speed across media items, so a correction running when the
        // episode changes would be inherited by an episode it was never about.
        normalSpeed()
        port.openEpisode(animeId, message.episode, message.translationId, 0)
        announce(TogetherEvent.Notice(NoticeKind.EPISODE, peerName, episode = message.episode))
        mentionVoice(message.translationId)
    }

    private suspend fun reported(message: TogetherMessage.State) {
        peer = PeerReport(message.positionMs, message.playing, message.sentAt, clock.millis())
        // Only a friend who means to be playing. One who is paused and buffering is simply
        // paused — and that reaches this side as a Pause, never as a report.
        peerIsLoading(message.buffering && message.playing)
        // Whatever episode they are in, this is where they are in it: a stashed episode change is
        // no longer the last word on their position.
        movedSinceReport = false
        // The same extrapolation the policy judges by, so the gap on screen is the gap being
        // acted on. Read off the raw report instead, the two differ by the one-way delay less the
        // clock offset — enough, between phones whose clocks are a second apart, to show a steady
        // drift the session can plainly see is not there.
        drift = port.state.value.positionMs - (reportedPositionNow() ?: return)
        republishLive()
        if (syncOnReport) {
            syncOnReport = false
            correct()
        }
    }

    /**
     * The friend's player is filling its buffer, so this one waits instead of running ahead.
     *
     * Both sides have always reported it and neither has ever read it. What that looked like: one
     * phone stalls on a segment, the other plays on, the gap passes ten seconds, and the rule says
     * seek — so the stalled phone is dragged forward, stalls again on the segment it has not got,
     * and is dragged again. «Перемотал на» every few seconds for as long as the network is slow.
     * Waiting is what a person would do.
     */
    /** The stall this jump is about to cause is not evidence of anything. */
    private fun settleAfterSeek() {
        correctionSettledAt = clock.millis() + CORRECTION_QUIET_MS
        peer = null
    }

    private suspend fun peerIsLoading(loading: Boolean) {
        if (loading == peerLoading) return
        peerLoading = loading
        if (loading) {
            if (!port.state.value.playing) return
            heldForPeer = true
            holdUntil = clock.millis() + PEER_LOADING_HOLD_MS
            if (correcting) forceNormalSpeed()
            port.pause()
            announce(TogetherEvent.Notice(NoticeKind.CATCHING_UP, peerName))
        } else if (heldForPeer) {
            releaseHold()
        }
    }

    /**
     * Let go of a hold — because the friend is ready, or because they have taken so long that a
     * held picture with nothing on screen explaining it is worse than being out of step.
     */
    private suspend fun releaseHold() {
        heldForPeer = false
        holdUntil = 0
        // Nothing is corrected against a report taken while the picture stood still.
        peer = null
        port.play()
    }

    /**
     * The friend's socket went away. It is not the end: a room keeps the seat for half a minute,
     * which is about how long a train takes to leave a tunnel.
     */
    private suspend fun departed(deliberate: Boolean) {
        if (peerName.isNotEmpty()) announce(TogetherEvent.Notice(NoticeKind.LEFT, peerName))
        rejoin?.cancel()
        rejoin = null
        rejoining = false
        if (deliberate) {
            // Somebody pressed «выйти». There is nobody to wait half a minute for, and «связь с
            // другом потеряна» after that wait would be an account of what happened that is simply
            // untrue.
            forceNormalSpeed()
            // Settled before the channel is taken down, not after. `stop()` cancels the job this
            // is running on, so anything written after it survives only because something further
            // down swallows the cancellation — which is a `runCatching` that exists for an
            // entirely different reason and could be tightened at any time.
            _state.value = SessionState.Ended
            stop()
            return
        }
        rejoining = true
        rejoin = scope.launch(failures) {
            delay(REJOIN_WINDOW_MS)
            // Closed before the window's own verdict, so the exception the guard makes for a
            // returning hello lasts the window rather than the rest of the session.
            rejoin = null
            rejoining = false
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
        // A ceiling on the whole clip, not just on each frame. Thirty seconds of Opus is ninety
        // kilobytes or so, and anything past this many is a caller with a bug rather than somebody
        // with a lot to say — better dropped here than cut into frames and sent.
        if (channel == null || bytes.isEmpty() || bytes.size > MAX_VOICE_BYTES) return
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
            // Deliberately not recorded as a reason: this is one action that did not make it out,
            // and the channel's own last word is what says why a session ended.
            Unit
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

        /** How often an unanswered greeting is said again — the same three seconds as on iOS. */
        const val HELLO_RETRY_MS = 3_000L

        /**
         * The longest this side holds a paused picture for a friend who is still loading. Past it,
         * waiting has become a frozen screen with no explanation, and being out of step is better.
         */
        const val PEER_LOADING_HOLD_MS = 20_000L

        /** The quiet after a jump, during which nothing is corrected. */
        const val CORRECTION_QUIET_MS = 3_000L

        /** Where this side is, often enough for the other to measure drift against. */
        const val STATE_INTERVAL_MS = 1_000L

        /** How often the gap is looked at. Twice the report interval, so it is never acting blind. */
        const val SYNC_INTERVAL_MS = 2_000L

        /** Past this, the friend's last report is too old to extrapolate from. */
        const val STALE_STATE_MS = 5_000L

        /** How far a hello is carried forward with no report to go on. A join screen lives half as long. */
        const val HELLO_CARRY_MAX_MS = 60_000L

        /** A clip nobody finished sending is not worth holding on to. */
        const val VOICE_TIMEOUT_MS = 30_000L

        /**
         * Where a gap gets seeked on a player with no speed control.
         *
         * Half of [SyncPolicy.SEEK_MS]: a receiver cannot be nudged, so the choice is a jump or
         * nothing, and a jump under a second costs more than it buys.
         */
        const val RATELESS_SEEK_MS = 1_000L

        /** Eight frames — comfortably past thirty seconds of Opus, and nowhere near a frame cap. */
        const val MAX_VOICE_BYTES = 8 * TogetherMessage.MAX_VOICE_CHUNK_BYTES

        /** Three, because one is a packet and two is bad luck. */
        const val GARBLED_LIMIT = 3

        private const val EVENT_BUFFER = 64

        private const val TAG = "TogetherSession"
    }
}
