package app.kaeru.ui.common.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.PendingWatchLink
import app.kaeru.domain.together.PeerHello
import app.kaeru.domain.together.PlaybackPort
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherSessionApi
import app.kaeru.domain.together.VoiceCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.math.abs
import javax.inject.Inject

/** How long a line stays in the corner before it fades. Long enough to look away from the video. */
private const val ITEM_LIFE_MS = 7_000L

/** The fade at the end of those seven seconds, which is part of them rather than added to them. */
private const val ITEM_FADE_MS = 300L

/** At most three, because the corner of a phone in landscape is about 147dp tall. */
private const val STACK_MAX = 3

/** Long enough to read, short enough that it is gone before it is in the way. */
private const val NOTICE_LIFE_MS = 3_000L

private const val REACTION_LIFE_MS = 1_200L

/** Three at once. Beyond that a tapped-out friend produces rain rather than a reaction. */
private const val REACTION_MAX = 3

/** Nothing waits longer than this for anybody. */
private const val WAIT_TIMEOUT_MS = 30_000L

/**
 * How long «Сессия закончилась» stays up.
 *
 * It is a receipt for something the viewer just did, not a state to be got out of — the session is
 * already over and its chip is already gone, so a line with an exit button and nothing behind it
 * would be the only thing on screen that could not be dismissed.
 */
private const val ENDED_LINE_MS = 3_000L

/**
 * How close the two have to get before «догоняет» stops being true.
 *
 * The same two seconds the sync policy uses as the line between «pull with playback speed» and
 * «seek»: under it the gap is being closed silently and there is nothing left to tell anybody.
 */
private const val CAUGHT_UP_MS = 2_000L

/**
 * A shared viewing as the two screens that show one need it.
 *
 * This is the mapping layer and nothing else: it holds no socket, no player and no timer that the
 * session could hold instead. What it does own is everything that ends by itself — the seven
 * seconds a message lives in the corner, the second a reaction flies for, the three seconds of a
 * notice, and the thirty seconds after which a wait stops being a wait.
 *
 * Two instances of this exist at once and that is deliberate: the join screen lives in the
 * browsing activity and the overlay in the player, which are different windows. They agree because
 * the session under them is one object, not because they share a view model.
 *
 * What this viewer says is put on screen here rather than waited for as an echo from the session.
 * A message that appears only once the network has confirmed it is a message that does not appear
 * when the network is the thing that is wrong, and the whole point of the corner is that it is
 * conversation rather than delivery receipts. Events that come back marked as this viewer's own
 * are therefore ignored.
 */
@HiltViewModel
class TogetherViewModel @Inject constructor(
    private val session: TogetherSessionApi,
    private val accounts: AccountRepository,
    private val library: LibraryRepository,
    private val pending: PendingWatchLink,
    private val clock: Clock,
    /** The microphone, watched for whether it is open — never driven from here. */
    private val microphone: VoiceCapture,
    /** The picture, for the one thing this screen does to it: turning it down while somebody talks. */
    private val port: PlaybackPort,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TogetherUiState())
    val uiState: StateFlow<TogetherUiState> = _uiState.asStateFlow()

    /** Ids for what this viewer says. Negative, so they can never collide with the session's. */
    private var localId = 0L

    /** The room the join screen is about, kept so «Повторить» has something to knock on again. */
    private var room: RoomLink? = null

    /** Whether things in the corner are allowed to disappear on a timer. TalkBack says no. */
    private var autoHide = true

    private var noticeJob: Job? = null
    private var waitJob: Job? = null
    private var timedWait: TimedWait? = null
    private var animeJob: Job? = null

    /** Which show the join screen is currently describing, so a second invitation redescribes. */
    private var describedAnime: Int? = null

    /** Whether «<имя> догоняет…» is up. It has no clock: it ends when the gap does. */
    private var catchingUp = false

    /**
     * Whether this screen ever saw the session running.
     *
     * The session is one per process and stays where it settled, so a player opened afterwards
     * collects `Ended` the moment it subscribes. A receipt is for the person who pressed the
     * button; a screen that never saw the session it is about has nothing to be told.
     */
    private var sawSession = false

    /**
     * Whether a player screen has attached to this instance.
     *
     * Two of these exist at once (see the class comment), and only the one under the player may
     * turn the picture down: the browsing activity's copy hears every clip too, never hears that
     * one finished, and would otherwise hold the episode quiet until the session ended.
     */
    private var attached = false

    /** Whether this screen has the picture turned down right now. */
    private var ducked = false

    private val expiryJobs = mutableMapOf<Long, Job>()

    /** The waits this screen times itself rather than trusting somebody else to end. */
    private enum class TimedWait(val afterMs: Long) {
        HOSTING(WAIT_TIMEOUT_MS),
        JOINING(WAIT_TIMEOUT_MS),
        ENDED(ENDED_LINE_MS),
    }

    init {
        viewModelScope.launch { session.state.collect(::applySession) }
        viewModelScope.launch { session.events.collect(::applyEvent) }
    }

    // --- what the viewer does --------------------------------------------------------------------

    /** Opens a room for this episode and hands the screen something to send to a friend. */
    fun share(title: String, episode: Int) {
        viewModelScope.launch {
            val link = session.host(displayName())
            val https = link.toHttps()
            _uiState.update {
                it.copy(share = ShareRequest(TogetherCopy.shareText(title, episode, https), https))
            }
        }
    }

    /** The share sheet has been opened; the invitation is not offered a second time. */
    fun shareShown() = _uiState.update { it.copy(share = null) }

    /**
     * An invitation has arrived, but not necessarily anywhere it can be shown.
     *
     * Parked rather than opened, because the flow the landing page prescribes — install the APK,
     * open the link again — always arrives at a signed-out app, and a room joined behind a login
     * screen is a room nobody is in with a clock running against nobody.
     */
    fun offer(uri: String) = pending.offer(uri)

    /** Whether something is waiting, for a login screen that owes the person an explanation. */
    val invitationWaiting: StateFlow<String?> get() = pending.link

    /** Takes whatever was parked, once there is a signed-in shell to put it on. Once only. */
    fun openPending() {
        val uri = pending.take() ?: return
        open(uri)
    }

    /**
     * Knocks on the room a link names, which is what makes the other phone say what it is watching.
     *
     * The knock happens before the viewer agrees to anything, because the screen they are deciding
     * on is built out of the answer: without it there is a room id and nothing a person could
     * possibly base a decision on.
     */
    fun open(uri: String) {
        val link = RoomLink.parse(uri).getOrNull()
        if (link == null) {
            room = null
            _uiState.update {
                it.copy(join = JoinUiState(loading = false, error = TogetherCopy.BAD_LINK, retryable = false))
            }
            return
        }
        // This phone's own invitation, tapped in the chat it was sent to. Nothing to join and
        // nothing to draw: the room is already here, with the friend in it or on their way.
        if (session.isHosting(link)) {
            room = null
            _uiState.update { it.copy(join = null) }
            return
        }
        room = link
        _uiState.update { it.copy(join = JoinUiState(loading = true)) }
        viewModelScope.launch { session.join(link, displayName()) }
    }

    /**
     * «Присоединиться», and «Повторить» after a failure: makes sure the room really is joined.
     *
     * The episode itself is opened by the screen, which is the only thing that can start an
     * activity. What happens here is the half that outlives it: a room already being joined is
     * left alone, and one that timed out or dropped is knocked on again rather than assumed.
     */
    fun join() {
        val link = room ?: return
        val current = session.state.value
        if (current is SessionState.Joining || current is SessionState.Live) return
        _uiState.update { it.copy(join = (it.join ?: JoinUiState()).copy(loading = true, error = null)) }
        viewModelScope.launch { session.join(link, displayName()) }
    }

    /**
     * What the player should be opened at, read at the moment of the press.
     *
     * The invitation is stale by however long it sat on screen: the friend has watched on, and
     * their autoplay may have run into the next episode. A player opened on the hello jumps
     * visibly once the session catches up, or opens the wrong episode entirely. The session has
     * been keeping up with them meanwhile, and its answer is what the episode opens at; the
     * hello stands in for a friend who has said nothing since.
     */
    fun joinTarget(): JoinTarget {
        val join = _uiState.value.join
        val moved = (session.state.value as? SessionState.Joining)?.pendingEpisode
        return JoinTarget(
            episode = moved ?: join?.episode ?: 0,
            positionMs = (session.peerPositionNow() ?: join?.positionMs ?: 0L).coerceAtLeast(0),
        )
    }

    /**
     * The player is open on the room's episode, so the screen that asked about it is done.
     *
     * Not the same thing as [dismissJoin]: the session carries on, and only the screen goes. Left
     * up, it would sit under the player and be the first thing the viewer saw on the way back out
     * of an episode they are already watching.
     */
    fun joinScreenDone() = _uiState.update { it.copy(join = null) }

    /**
     * «Не сейчас», and the system back button, which means the same thing.
     *
     * [TogetherSessionApi.watchAlone] and nothing else. It is what abandons a join that has not
     * become a session, and it is the only one of the two that leaves a state this screen can come
     * back from: [TogetherSessionApi.leave] ends in `Ended`, and a second invitation in the same
     * process would then be knocking on an engine that has been told the session is over. If the
     * host is owed a goodbye, that belongs to the engine's own handling of an abandoned join.
     */
    fun dismissJoin() {
        room = null
        armWait(null)
        session.watchAlone()
        _uiState.update { it.copy(join = null, wait = null) }
    }

    /**
     * «Смотреть дальше» / «Смотреть одному»: the evening carries on.
     *
     * Which of the two it was decides what is left behind. A wait is only a wait, so leaving it
     * keeps the session — the friend may still walk in. A session that is already lost or over has
     * nothing left to keep, so the same press closes it and puts the player back to itself.
     */
    fun leaveWait() {
        armWait(null)
        catchingUp = false
        val phase = _uiState.value.phase
        if (phase == TogetherPhase.LOST || phase == TogetherPhase.ENDED) {
            leave()
            _uiState.update { it.copy(wait = null) }
            return
        }
        session.watchAlone()
        _uiState.update { it.copy(wait = null, join = it.join?.copy(error = null, loading = false)) }
    }

    /** The chip's «Выйти из совместного просмотра». Said out loud, because it was deliberate. */
    fun leave() {
        armWait(null)
        // The corner is emptied here rather than left to the state that follows: the engine settles
        // in `Ended` and a `StateFlow` does not re-emit a value it is already holding, so a second
        // press of an exit would otherwise have nothing to act on.
        _uiState.update { it.copy(wait = null, message = TogetherCopy.LEFT_SESSION) }
        viewModelScope.launch { session.leave() }
    }

    /**
     * The player screen is going away. Ends the session if it is going away for good.
     *
     * Rotation, the background and a floating window all leave the session running, which is what
     * the spec asks for and what a viewer expects: the conversation is about the episode, not
     * about which way up the phone is.
     */
    fun playerGone(finishing: Boolean, changingConfigurations: Boolean) {
        if (!SessionLifecycle.endsSession(finishing, changingConfigurations)) return
        if (_uiState.value.phase == TogetherPhase.IDLE) return
        armWait(null)
        // The engine does this on its own scope, so the goodbye survives this view model being
        // cleared moments from now.
        viewModelScope.launch { session.leave() }
    }

    /**
     * A player screen has just opened on this view model.
     *
     * The session is one per process and stays where it settled, so a screen opened after one
     * ended would draw its receipt over an ordinary episode that has nothing to do with it. Only
     * that one line is cleared: a screen opening into a session that is still running has waits
     * worth keeping.
     */
    fun playerAttached() {
        if (!attached) {
            attached = true
            viewModelScope.launch { quietWhileTalking() }
        }
        if (sawSession || _uiState.value.phase != TogetherPhase.ENDED) return
        armWait(null)
        _uiState.update { it.copy(wait = null) }
    }

    /**
     * The episode is turned down while a clip is coming out of the speaker and while the
     * microphone is open — from the first frame of the hold to whatever ends it: a release, a
     * swipe, the thirty-second ceiling, the app going to the background. When the two overlap it
     * stays down until the last of them ends.
     *
     * Explicit rather than left to audio focus: the phone will not duck an app against itself,
     * and the microphone takes no focus at all.
     */
    private suspend fun quietWhileTalking() {
        combine(microphone.recording, _uiState.map { it.playing != null }) { held, clip -> held || clip }
            .distinctUntilChanged()
            .collect { quiet ->
                if (quiet == ducked) return@collect
                ducked = quiet
                port.duck(quiet)
            }
    }

    override fun onCleared() {
        // The collector above dies with the scope, so an episode this screen turned down is put
        // back here rather than left quiet for the rest of the evening.
        if (ducked) port.duck(false)
    }

    fun sendChat(text: String) {
        val trimmed = text.trim().take(TogetherCopy.MAX_CHARS)
        if (trimmed.isEmpty()) return
        viewModelScope.launch { session.sendChat(trimmed) }
        add(ConversationItem(id = --localId, mine = true, author = TogetherCopy.YOU, text = trimmed, at = clock.millis()))
    }

    fun sendReaction(kind: ReactionKind) {
        viewModelScope.launch { session.sendReaction(kind) }
        fly(kind, mine = true)
    }

    fun sendVoice(bytes: ByteArray, durationMs: Int) {
        viewModelScope.launch { session.sendVoice(bytes, durationMs) }
        add(
            ConversationItem(
                id = --localId,
                mine = true,
                author = TogetherCopy.YOU,
                clip = VoiceClipItem(bytes, durationMs),
                at = clock.millis(),
            ),
        )
    }

    /** The clip that was playing has finished, or was never started. */
    fun clipPlayed() = _uiState.update { it.copy(playing = null) }

    /** «Послушать ещё раз», from the corner or from the history. */
    fun replay(id: Long) = _uiState.update { state ->
        val clip = state.history.firstOrNull { it.id == id }?.clip ?: return@update state
        state.copy(playing = PlayingClip(id, clip.bytes, clip.durationMs))
    }

    fun microphoneDenied() = _uiState.update { it.copy(message = TogetherCopy.MIC_DENIED) }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    fun openHistory() = _uiState.update { it.copy(historyOpen = true) }

    fun closeHistory() = _uiState.update { it.copy(historyOpen = false) }

    /**
     * Whether the corner is allowed to empty itself.
     *
     * Turned off with a screen reader running: text that vanishes on a timer is the one thing
     * WCAG 2.2.1 will not have, and somebody listening to the screen cannot be made to race it.
     */
    fun setAutoHide(enabled: Boolean) {
        if (autoHide == enabled) return
        autoHide = enabled
        if (!enabled) {
            expiryJobs.values.forEach(Job::cancel)
            expiryJobs.clear()
        }
    }

    /** The other half of [setAutoHide]: the corner emptied by hand rather than by a clock. */
    fun clearStack() = _uiState.update { it.copy(stack = emptyList()) }

    // --- what the session does --------------------------------------------------------------------

    private fun applySession(state: SessionState) {
        // Anything but the two resting states is this screen watching a session happen, which is
        // what earns it the receipt when that session ends.
        if (state !is SessionState.Idle && state !is SessionState.Ended) sawSession = true
        when (state) {
            SessionState.Idle -> {
                armWait(null)
                forget()
                _uiState.update { it.copy(phase = TogetherPhase.IDLE, peerName = null, wait = null) }
            }
            is SessionState.Hosting -> {
                if (startingFresh()) forget()
                armWait(if (state.waiting) TimedWait.HOSTING else null)
                _uiState.update {
                    it.copy(
                        phase = TogetherPhase.HOSTING,
                        wait = if (state.waiting) WaitLine(TogetherCopy.WAITING_FRIEND, WaitExit.KEEP_WATCHING) else null,
                    )
                }
            }
            is SessionState.Joining -> {
                if (startingFresh()) forget()
                // One clock for the whole of joining, from the knock until `Live`. The hello
                // arriving is the other phone answering, not the session starting: everything
                // that can still stall — resolving this viewer's own stream, the first frames,
                // the first sync — happens after it, and a wait that loses its timeout at the
                // moment it becomes permanent is the failure this feature is built against.
                armWait(TimedWait.JOINING)
                state.hello?.let(::describe)
                _uiState.update {
                    it.copy(
                        phase = TogetherPhase.JOINING,
                        peerName = state.hello?.name ?: it.peerName,
                        wait = WaitLine(TogetherCopy.CONNECTING, WaitExit.WATCH_ALONE),
                        join = (it.join ?: JoinUiState()).withHello(state.hello),
                    )
                }
            }
            is SessionState.Live -> {
                armWait(null)
                // «Догоняет» ends when the gap does, which is the only honest end for it: a clock
                // would take the line away while the friend was still behind.
                if (catchingUp && abs(state.driftMs) < CAUGHT_UP_MS) catchingUp = false
                _uiState.update {
                    it.copy(
                        phase = TogetherPhase.LIVE,
                        peerName = state.peerName,
                        wait = it.wait.takeIf { _ -> catchingUp },
                    )
                }
            }
            is SessionState.Lost -> stop(TogetherPhase.LOST, TogetherCopy.lost(state.reason))
            SessionState.Ended -> {
                stop(TogetherPhase.ENDED, TogetherCopy.ENDED)
                // Only for the screen that watched it end. Anything else is being handed somebody
                // else's receipt over an episode that has nothing to do with it.
                if (sawSession) armWait(TimedWait.ENDED) else _uiState.update { it.copy(wait = null) }
            }
        }
    }

    private fun stop(phase: TogetherPhase, message: String) {
        armWait(null)
        // Otherwise the flag outlives the session it belonged to, and the `Live` branch — which
        // keeps whatever is in `wait` while somebody is catching up — would carry «Связь с другом
        // потеряна» into a session that has just come back.
        catchingUp = false
        _uiState.update {
            it.copy(
                phase = phase,
                wait = WaitLine(message, WaitExit.WATCH_ALONE),
                join = it.join?.copy(loading = false, error = message),
            )
        }
    }

    private fun applyEvent(event: TogetherEvent) {
        when (event) {
            // «Догоняет» is a wait, not a remark: the viewer's own video is fine and the friend is
            // behind, so what the screen owes them is the state and a way to stop caring about it.
            // Everything else the other phone does is a remark, and a later one is proof that the
            // catching up finished.
            is TogetherEvent.Notice -> if (event.kind == NoticeKind.CATCHING_UP) {
                catchingUp = true
                _uiState.update {
                    it.copy(wait = WaitLine(TogetherCopy.notice(event), WaitExit.KEEP_WATCHING))
                }
            } else {
                if (catchingUp) {
                    catchingUp = false
                    _uiState.update { it.copy(wait = null) }
                }
                notice(TogetherCopy.notice(event))
            }
            is TogetherEvent.ChatItem -> if (event.fromPeer) {
                add(ConversationItem(event.id, mine = false, author = peer(), text = event.text, at = event.at))
            }
            is TogetherEvent.ReactionEvent -> if (event.fromPeer) fly(event.kind, mine = false)
            is TogetherEvent.VoiceClip -> if (event.fromPeer) {
                add(
                    ConversationItem(
                        id = event.id,
                        mine = false,
                        author = peer(),
                        clip = VoiceClipItem(event.bytes, event.durationMs),
                        at = clock.millis(),
                    ),
                )
                // Straight through the speaker: a remark about what is on screen right now is not
                // worth anything twenty seconds later, behind a tap nobody made.
                _uiState.update { it.copy(playing = PlayingClip(event.id, event.bytes, event.durationMs)) }
            }
        }
    }

    // --- the parts that end by themselves ----------------------------------------------------------

    private fun add(item: ConversationItem) {
        _uiState.update {
            it.copy(
                stack = (it.stack + item).takeLast(STACK_MAX),
                history = it.history + item,
            )
        }
        if (!autoHide) return
        expiryJobs[item.id] = viewModelScope.launch {
            // Marked first and removed after, so the corner has something to fade rather than a
            // line that vanishes between two frames.
            delay(ITEM_LIFE_MS - ITEM_FADE_MS)
            _uiState.update { state ->
                state.copy(stack = state.stack.map { if (it.id == item.id) it.copy(leaving = true) else it })
            }
            delay(ITEM_FADE_MS)
            expiryJobs -= item.id
            _uiState.update { state -> state.copy(stack = state.stack.filterNot { it.id == item.id }) }
        }
    }

    private fun fly(kind: ReactionKind, mine: Boolean) {
        val reaction = FlyingReaction(--localId, kind, mine)
        var added = false
        _uiState.update {
            if (it.reactions.size >= REACTION_MAX) return@update it
            added = true
            it.copy(reactions = it.reactions + reaction)
        }
        if (!added) return
        viewModelScope.launch {
            delay(REACTION_LIFE_MS)
            _uiState.update { state -> state.copy(reactions = state.reactions.filterNot { it.id == reaction.id }) }
        }
    }

    private fun notice(text: String) {
        noticeJob?.cancel()
        val line = NoticeLine(--localId, text)
        _uiState.update { it.copy(notice = line) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_LIFE_MS)
            _uiState.update { state -> state.copy(notice = state.notice?.takeIf { it.id != line.id }) }
        }
    }

    /**
     * Starts, restarts or cancels the clock on a wait.
     *
     * Thirty seconds and then something else happens, whatever that something is: a room that
     * nobody walked into simply stops saying so, and an invitation nobody answered becomes a
     * failure with a way forward. Neither of them leaves a person looking at a spinner.
     */
    private fun armWait(kind: TimedWait?) {
        if (kind == timedWait) return
        timedWait = kind
        waitJob?.cancel()
        if (kind == null) return
        waitJob = viewModelScope.launch {
            delay(kind.afterMs)
            timedWait = null
            when (kind) {
                // The friend may still be reading the message. The line goes; the room stays.
                TimedWait.HOSTING -> _uiState.update { it.copy(wait = null) }
                TimedWait.JOINING -> _uiState.update {
                    it.copy(
                        wait = WaitLine(TogetherCopy.UNREACHABLE, WaitExit.WATCH_ALONE),
                        join = it.join?.copy(loading = false, error = TogetherCopy.UNREACHABLE),
                    )
                }
                // Said, read and gone. Nothing is waiting on the other side of this one.
                TimedWait.ENDED -> _uiState.update { it.copy(wait = null) }
            }
        }
    }

    /** Whether the next session is a new one, so the last one's conversation goes with it. */
    private fun startingFresh(): Boolean = _uiState.value.phase in
        setOf(TogetherPhase.IDLE, TogetherPhase.LOST, TogetherPhase.ENDED)

    /**
     * What the other phone is watching, named and illustrated out of this device's own catalogue.
     *
     * Keyed on the show rather than on whether a job exists: the collection never ends, so a
     * second invitation — a different friend, a different show — would otherwise find the old job
     * still running and leave the new screen on skeletons for good.
     */
    private fun describe(hello: PeerHello) {
        if (describedAnime == hello.animeId) return
        describedAnime = hello.animeId
        animeJob?.cancel()
        animeJob = viewModelScope.launch {
            launch { library.refreshAnime(hello.animeId) }
            library.observeAnimeDetails(hello.animeId).collect { anime ->
                _uiState.update { state ->
                    val join = state.join ?: return@update state
                    state.copy(
                        join = join.copy(
                            title = anime?.title,
                            posterUrl = anime?.posterUrl,
                            line = anime?.let {
                                TogetherCopy.joinLine(hello.name, it.title, hello.episode, hello.positionMs)
                            },
                        ),
                    )
                }
            }
        }
    }

    /** A session that is over takes its conversation with it. Nothing here is written down. */
    private fun forget() {
        expiryJobs.values.forEach(Job::cancel)
        expiryJobs.clear()
        noticeJob?.cancel()
        animeJob?.cancel()
        animeJob = null
        describedAnime = null
        catchingUp = false
        _uiState.update {
            it.copy(
                stack = emptyList(),
                history = emptyList(),
                historyOpen = false,
                reactions = emptyList(),
                notice = null,
                playing = null,
            )
        }
    }

    private fun peer(): String = TogetherCopy.name(_uiState.value.peerName)

    private suspend fun displayName(): String =
        accounts.account.firstOrNull()?.nickname?.takeIf { it.isNotBlank() } ?: TogetherCopy.SOMEBODY
}

/** The half of the join screen that only exists once the other phone has answered. */
private fun JoinUiState.withHello(hello: PeerHello?): JoinUiState = if (hello == null) {
    copy(loading = true, error = null)
} else {
    copy(
        loading = false,
        error = null,
        peerName = hello.name,
        animeId = hello.animeId,
        episode = hello.episode,
        positionMs = hello.positionMs,
    )
}
