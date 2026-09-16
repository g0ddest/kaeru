package app.kaeru.ui.common.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.together.PeerHello
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherSessionApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** How long a line stays in the corner before it fades. Long enough to look away from the video. */
private const val ITEM_LIFE_MS = 7_000L

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
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TogetherUiState())
    val uiState: StateFlow<TogetherUiState> = _uiState.asStateFlow()

    /** Ids for what this viewer says. Negative, so they can never collide with the session's. */
    private var localId = 0L

    /** The room the join screen is about, kept so «Повторить» has something to knock on again. */
    private var pending: RoomLink? = null

    /** Whether things in the corner are allowed to disappear on a timer. TalkBack says no. */
    private var autoHide = true

    private var noticeJob: Job? = null
    private var waitJob: Job? = null
    private var timedWait: TimedWait? = null
    private var animeJob: Job? = null
    private val expiryJobs = mutableMapOf<Long, Job>()

    /** The two waits this screen times itself rather than trusting somebody else to end. */
    private enum class TimedWait { HOSTING, JOINING }

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
     * Knocks on the room a link names, which is what makes the other phone say what it is watching.
     *
     * The knock happens before the viewer agrees to anything, because the screen they are deciding
     * on is built out of the answer: without it there is a room id and nothing a person could
     * possibly base a decision on.
     */
    fun open(uri: String) {
        val link = RoomLink.parse(uri).getOrNull()
        if (link == null) {
            pending = null
            _uiState.update { it.copy(join = JoinUiState(loading = false, error = TogetherCopy.BAD_LINK)) }
            return
        }
        pending = link
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
        val link = pending ?: return
        val current = session.state.value
        if (current is SessionState.Joining || current is SessionState.Live) return
        _uiState.update { it.copy(join = (it.join ?: JoinUiState()).copy(loading = true, error = null)) }
        viewModelScope.launch { session.join(link, displayName()) }
    }

    /**
     * The player is open on the room's episode, so the screen that asked about it is done.
     *
     * Not the same thing as [dismissJoin]: the session carries on, and only the screen goes. Left
     * up, it would sit under the player and be the first thing the viewer saw on the way back out
     * of an episode they are already watching.
     */
    fun joinScreenDone() = _uiState.update { it.copy(join = null) }

    /** «Не сейчас»: the room is let go of, not left open behind a closed screen. */
    fun dismissJoin() {
        pending = null
        _uiState.update { it.copy(join = null) }
        viewModelScope.launch { session.leave() }
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
        val phase = _uiState.value.phase
        if (phase == TogetherPhase.LOST || phase == TogetherPhase.ENDED) {
            leave()
            return
        }
        session.watchAlone()
        _uiState.update { it.copy(wait = null, join = it.join?.copy(error = null, loading = false)) }
    }

    fun leave() {
        armWait(null)
        viewModelScope.launch { session.leave() }
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
        when (state) {
            SessionState.Idle -> {
                armWait(null)
                forget()
                _uiState.update { it.copy(phase = TogetherPhase.IDLE, peerName = null, wait = null) }
            }
            is SessionState.Hosting -> {
                armWait(if (state.waiting) TimedWait.HOSTING else null)
                _uiState.update {
                    it.copy(
                        phase = TogetherPhase.HOSTING,
                        wait = if (state.waiting) WaitLine(TogetherCopy.WAITING_FRIEND, WaitExit.KEEP_WATCHING) else null,
                    )
                }
            }
            is SessionState.Joining -> {
                armWait(if (state.hello == null) TimedWait.JOINING else null)
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
                _uiState.update {
                    it.copy(phase = TogetherPhase.LIVE, peerName = state.peerName, wait = null)
                }
            }
            is SessionState.Lost -> stop(TogetherPhase.LOST, TogetherCopy.lost(state.reason))
            SessionState.Ended -> stop(TogetherPhase.ENDED, TogetherCopy.ENDED)
        }
    }

    private fun stop(phase: TogetherPhase, message: String) {
        armWait(null)
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
            is TogetherEvent.Notice -> notice(TogetherCopy.notice(event))
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
            delay(ITEM_LIFE_MS)
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
            delay(WAIT_TIMEOUT_MS)
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
            }
        }
    }

    /** What the other phone is watching, named and illustrated out of this device's own catalogue. */
    private fun describe(hello: PeerHello) {
        if (animeJob != null) return
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
