package app.kaeru.player

import androidx.media3.common.Player
import app.kaeru.di.IoDispatcher
import app.kaeru.di.LocalEngine
import app.kaeru.di.PlaybackScope
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.playback.AddStartedTitleToList
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.SkipKind
import app.kaeru.domain.playback.SkipMarks
import app.kaeru.domain.playback.SkipMarksSource
import app.kaeru.domain.playback.SkipRules
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.together.LocalAction
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Who asked for a playback action.
 *
 * [REMOTE] is a friend's, arriving over a shared session and applied here. The difference matters
 * for exactly one reason: a remote action must not be announced back to the friend who made it,
 * which is how two phones talk each other into a loop.
 */
enum class ActionOrigin { LOCAL, REMOTE }

/**
 * Playback as the rest of the app sees it: episodes, tracks and positions rather than
 * media sources and surfaces.
 *
 * One instance serves the whole process, so the phone screen, the TV screen and the media
 * session all watch the same [state] and a rotation or a trip through the notification
 * shade never restarts anything.
 *
 * Everything here is called from the main thread.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /**
     * Things that are announced once: see [PlaybackEvent]. Buffered, so an announcement made
     * while no screen is listening waits for the next one instead of being dropped.
     */
    val events: Flow<PlaybackEvent>

    /** The Media3 player a video surface attaches to, while there is one. */
    val videoPlayer: StateFlow<Player?>

    /**
     * What this viewer did, for a shared session to pass on: a press, a scrub, an episode they
     * chose or one autoplay ran into for them.
     *
     * Only [ActionOrigin.LOCAL] calls appear here. What a session applied on a friend's behalf
     * does not, so forwarding everything on this flow cannot echo it back to them.
     */
    val localActions: Flow<LocalAction>

    /** Resolves [target], points the engine at it and starts playing. Suspends until playback is under way. */
    suspend fun play(target: PlaybackTarget, origin: ActionOrigin = ActionOrigin.LOCAL)

    fun togglePlayPause()

    /**
     * Plays or pauses, whichever [playing] asks for, rather than whichever this is not.
     *
     * A friend's «play» has to mean play: sending a toggle down this path would pause a phone
     * that was already playing, which is the one thing it must never do.
     */
    fun setPlaying(playing: Boolean, origin: ActionOrigin = ActionOrigin.LOCAL)

    fun seekTo(positionMs: Long, origin: ActionOrigin = ActionOrigin.LOCAL)

    fun seekBy(deltaMs: Long)

    /**
     * Steps over the opening the player is offering to step over, if it is offering one.
     *
     * A seek and nothing else, so a friend watching along is told the same thing they would be
     * told about a scrub — there is no «skip» in the protocol and there does not need to be.
     */
    fun skipOpening()

    /**
     * Plays slightly slow or slightly fast. `1.0` is normal speed.
     *
     * Only a shared session asks for this, to close a gap of a second or two without the jump a
     * seek would cost on HLS. Never a viewer action, so it is never announced as one.
     */
    fun setRate(factor: Float)

    /**
     * Turns the picture's sound down while somebody is talking over it, and back up afterwards.
     *
     * A shared viewing's request, never a viewer action, so it is never announced. It follows the
     * picture: an engine that takes over mid-voice is told, and a receiver that cannot be turned
     * down ignores it.
     */
    fun duck(on: Boolean)

    /** Same episode, same position, another voice. */
    suspend fun changeTranslation(translation: Translation)

    /** Same episode, same position, another rung of the quality ladder. */
    fun changeQuality(quality: Quality)

    /**
     * The episode after this one, in the same track. Announces
     * [PlaybackEvent.NextEpisodeUnavailable] and stays where it is if it cannot be started.
     */
    suspend fun playNext()

    /**
     * Hands playback to another engine: the Chromecast one when a session starts, the local one
     * when it ends. The episode, the track and the chosen quality all carry across, and so does
     * everything already decided about them — an episode counted as watched stays counted.
     *
     * [carryPositionMs] is where the new engine picks up. The caller reads it from [state]
     * before the engine that was playing is gone, because a receiver that has already
     * disconnected no longer has a position to give.
     *
     * Switching to the engine that is already playing does nothing.
     */
    suspend fun switchEngine(engine: PlaybackEngine, carryPositionMs: Long)

    /** The viewer said no to the countdown; the offer stays, the switch does not happen. */
    fun cancelAutoplay()

    /** Resolves and starts the current episode again after a failure, from where it stopped. */
    suspend fun retry()

    /**
     * A player screen is showing this playback from now on.
     *
     * Playback can outlive a screen — an episode left on a Chromecast plays on after the player
     * closes — and what happens when that session ends depends entirely on whether anyone is
     * there to see it. Every screen says so on its way in, including the one that finds the
     * episode it wanted already playing.
     */
    fun attachScreen()

    /** Writes the current position down now, because the screen is going away. */
    fun reportProgress()

    /** Stops playback, saves the position and forgets what was playing. */
    fun release()
}

/**
 * The state machine, driven entirely by what the [PlaybackEngine] reports.
 *
 * There are no timers here on purpose. A position tick *is* the passage of time: the
 * countdown to the next episode is what remains of the episode, and the five-second
 * save interval is five seconds of watched video. A paused player therefore freezes
 * the countdown and stops saving by itself, and every rule can be tested by handing the
 * controller a sequence of positions.
 *
 * Which engine reports is the only thing casting changes. [localEngine] is the one that
 * decodes on this phone; anything else is a receiver somewhere in the room, which is the
 * whole of what `isCasting` means here.
 */
@Singleton
class DefaultPlaybackController @Inject constructor(
    @param:LocalEngine private val localEngine: PlaybackEngine,
    private val resolve: ResolveEpisodeStream,
    private val deleteWatchedDownloads: DeferredDownloadRemoval,
    private val progress: WatchProgress,
    private val markWatched: MarkEpisodeWatched,
    private val addToList: AddStartedTitleToList,
    private val suppressedMarks: SuppressedMarks,
    private val library: LibraryRepository,
    private val prefs: PlaybackPreferences,
    private val headers: StreamHeaders,
    private val downloads: DownloadRepository,
    private val connectivity: Connectivity,
    private val skipMarks: SkipMarksSource,
    @param:PlaybackScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackController {

    /** One position, ready to be written, taken before whatever is about to change the row. */
    private data class Sample(
        val animeId: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val translationId: Int?,
        val season: Int?,
    )

    /**
     * One episode on its way to an engine: what was asked for, and how.
     *
     * Kept because a resolve takes seconds and a transition can be cancelled inside them — an
     * engine switch always is. Whoever cancels it inherits the job of finishing it, and only
     * this says which episode and whether it was a fresh one.
     */
    private data class Opening(
        val target: PlaybackTarget,
        val freshEpisode: Boolean,
        val preferQuality: Quality? = null,
        /**
         * This is the move to the next episode, so a failure is a passing message rather than
         * the error screen: the episode that just finished is still what the viewer is looking at.
         */
        val advancing: Boolean = false,
        /**
         * The viewer chose the voice this target names, rather than inheriting it.
         *
         * Only [changeTranslation] means that. An episode picked from the list and an episode
         * autoplay ran into both carry the voice that was playing, and neither is a request to
         * reconsider it — so neither may turn a download in another voice into a stream from
         * Kodik of a file already on the device.
         */
        val pickedTrack: Boolean = false,
        /**
         * A person on this phone asked for this episode, so a friend watching along should be
         * switched to it as well. False for the moves that are not a choice: a retry of the
         * episode already on screen, and an engine handing the same episode to a receiver.
         */
        val local: Boolean = true,
        /**
         * Who asked. Separate from [local], which is false for a retry too: a retry is still this
         * viewer's, and a voice they never pinned may still be stood in for on it. A friend's
         * episode arrives naming their voice, and that voice is played as named or not at all.
         */
        val origin: ActionOrigin = ActionOrigin.LOCAL,
    ) {
        /**
         * Whether another voice may stand in when the one this target names lacks the episode.
         * Never for a voice somebody chose — a pick from the chooser, or a friend's — and always
         * for the rest: an inherited voice is a habit, not an instruction.
         *
         * A friend's episode that names no voice at all named nobody's choice: the port hands one
         * over as null when this side's catalogue has nothing matching, and what plays then is
         * this side's own remembered voice. So it is stood in for like any other habit, rather
         * than failing the guest with «Серии N ещё нет в этой озвучке» over a voice they never
         * picked and cannot change from there.
         */
        val substitutable: Boolean
            get() = !pickedTrack && (origin == ActionOrigin.LOCAL || target.translation == null)
    }

    /**
     * One episode's links, and where they came from.
     *
     * [onDevice] is not a fact about the links but about who can use them: a downloaded episode
     * carries the address it was fetched from, whose signature expired hours ago and whose bytes
     * are in a cache only this phone can read.
     */
    private data class Source(val stream: EpisodeStream, val onDevice: Boolean, val insteadOf: Translation? = null)

    /** Settings are read once per episode: changing them mid-episode should not move the goalposts. */
    private data class Settings(
        val threshold: Float = 0.9f,
        val autoplay: Boolean = true,
        val quality: Quality? = null,
        val skipEnding: Boolean = false,
    )

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = Channel<PlaybackEvent>(Channel.BUFFERED)
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private val _videoPlayer = MutableStateFlow<Player?>(null)
    override val videoPlayer: StateFlow<Player?> = _videoPlayer.asStateFlow()

    /**
     * Dropping the oldest rather than suspending on purpose: this is written from the main thread
     * in the middle of starting a video, and a session that is not collecting — which is every
     * moment nobody is watching together — must not be able to stall a press of play.
     */
    private val _localActions = MutableSharedFlow<LocalAction>(
        extraBufferCapacity = ACTION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val localActions: Flow<LocalAction> = _localActions.asSharedFlow()

    /** Whichever engine is playing right now. The local one until a receiver takes over. */
    private var engine: PlaybackEngine = localEngine

    /** Following [engine]: its reports and the player a surface can attach to, if it has one. */
    private var following: Job? = null

    /** Casting is exactly "the engine playing is not this phone's". */
    private val casting: Boolean get() = engine !== localEngine

    private var settings = Settings()

    /** The one resolve-and-prepare at a time; while it runs, engine reports are not acted on. */
    private var transition: Job? = null
    private val switching: Boolean get() = transition?.isActive == true

    private var markedEpisode = false

    /** This episode's opening and ending, already sieved, or nothing known about them yet. */
    private var marks = SkipMarks.NONE

    /** Whether the one question per episode has been asked; an answer of «none» still counts. */
    private var marksAsked = false

    /** This episode's ending has already been stepped over, by a press or by itself. */
    private var endingSkipped = false

    /**
     * Where the engine said playback was last time, so an episode that walked into its ending can
     * be told from a bar dragged into it. Negative until the first report of an episode.
     */
    private var lastSeenMs = -1L

    /**
     * Until playback has carried this far, every position is a seek's rather than the episode's.
     * A jump lands inside the ending in one report, and a viewer checking how it ends has not
     * asked to be moved on.
     */
    private var settledAtMs = -1L

    /** Whether the sound is wanted down right now, so an engine taking over can be told. */
    private var ducked = false

    /**
     * What is playing came off the device rather than off the network. Only one thing turns on
     * it: handing the episode to a receiver has to resolve it again, because the links a
     * download carries lead nowhere from anywhere but here.
     */
    private var playingDownload = false
    private var reResolved = false
    private var autoplayCancelled = false
    private var lastReportedMs = -1L
    private var wasPlaying = false

    /** The progress write that owns [WatchProgress]'s queue, or the last one that did. */
    private var writing: Job? = null

    /** The episode a running transition is still resolving, or null when nothing is on its way. */
    private var opening: Opening? = null

    /**
     * Whether a player screen is showing this. True by default — playback only ever starts
     * because a screen asked for it — and false from the moment one goes away leaving an
     * episode on a receiver.
     */
    private var screenAttached = true

    // Last, not as a property initializer: following an engine starts reading its reports, and
    // on the main dispatcher that happens at once — before the fields those reports touch exist.
    init {
        following = follow(localEngine)
    }

    override suspend fun play(target: PlaybackTarget, origin: ActionOrigin) {
        transition {
            // Recorded before the flush, which suspends: an engine switch landing in that window
            // has to resume this episode rather than the one it supersedes.
            val plan = Opening(target, freshEpisode = true, local = origin == ActionOrigin.LOCAL, origin = origin)
            opening = plan
            flushProgressNow()
            _state.value = PlaybackState(
                target = target,
                isBuffering = true,
                positionMs = target.startPositionMs,
                isCasting = casting,
            )
            open(plan).onFailure(::fail)
        }.join()
    }

    override fun togglePlayPause() = setPlaying(!_state.value.isPlaying)

    override fun setPlaying(playing: Boolean, origin: ActionOrigin) {
        val current = _state.value
        if (!playing) {
            engine.pause()
            announce(origin) { LocalAction.Pause(_state.value.positionMs) }
            return
        }
        // Pressing play on an episode that ran out should replay it, not sit on the last frame.
        val replaying = current.durationMs > 0 && current.positionMs >= current.durationMs
        if (replaying) {
            engine.seekTo(0)
            _state.update { it.copy(positionMs = 0) }
        }
        engine.play()
        announce(origin) { LocalAction.Play(_state.value.positionMs) }
    }

    override fun seekTo(positionMs: Long, origin: ActionOrigin) {
        val clamped = EpisodeQueue.clampSeek(positionMs, _state.value.durationMs)
        // The offer moves with the position rather than waiting for the next report: pressing the
        // button has to take it off the screen at once, and a scrub into the opening is the same
        // arithmetic answered the same way.
        _state.update { it.copy(positionMs = clamped, skip = SkipRules.offer(marks, clamped, it.durationMs)) }
        // Where the viewer went, and how long the automatic skip stands down for afterwards. A
        // drag into the last minute is not an episode running into its ending.
        lastSeenMs = clamped
        settledAtMs = clamped + SkipRules.SEEK_SETTLE_MS
        engine.seekTo(clamped)
        announce(origin) { LocalAction.Seek(clamped) }
    }

    override fun setRate(factor: Float) = engine.setRate(factor)

    override fun duck(on: Boolean) {
        ducked = on
        engine.duck(on)
    }

    /** Says what this viewer did, and says nothing at all about what their friend did. */
    private inline fun announce(origin: ActionOrigin, action: () -> LocalAction) {
        if (origin == ActionOrigin.LOCAL) _localActions.tryEmit(action())
    }

    override fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun skipOpening() {
        val offer = _state.value.skip ?: return
        if (offer.kind != SkipKind.OPENING) return
        seekTo(offer.interval.endMs)
    }

    override suspend fun changeTranslation(translation: Translation) {
        transition {
            val current = _state.value.target ?: return@transition
            val sameEpisode = current.copy(startPositionMs = _state.value.positionMs, translation = translation)
            val plan = Opening(
                sameEpisode,
                freshEpisode = false,
                preferQuality = _state.value.quality,
                pickedTrack = true,
            )
            opening = plan
            flushProgressNow()
            _state.update { it.copy(isBuffering = true, ready = false, error = null) }
            open(plan).onFailure(::fail)
        }.join()
    }

    /**
     * Inside the guard like every other swap. A pick that landed during a track change, a retry
     * or an autoplay resolve used to prepare the stream being replaced and then be overridden by
     * that resolve, so the one thing that did not happen was what the viewer asked for.
     *
     * An episode still on its way is finished at the picked quality rather than dropped: it may
     * be a different episode or a different voice, and only the quality was being argued about.
     */
    override fun changeQuality(quality: Quality) {
        // Read before the guard replaces the transition; a cancelled resolve leaves its plan
        // behind precisely so whoever supersedes it can finish the job.
        val unfinished = opening?.takeIf { switching }
        transition {
            if (unfinished != null) {
                val plan = unfinished.copy(preferQuality = quality)
                opening = plan
                // The resolve this takes over was cancelled, possibly inside its own flush.
                // Resolving writes the row this episode owns, so the position reached has to be
                // on disk before that happens or the sample lands after it and puts the row back
                // on the episode being left.
                flushProgressNow()
                _state.update { it.copy(isBuffering = true, ready = false, error = null) }
                // The plan carries whatever it was: a move to the next episode that fails is
                // still a passing message, not the error screen over an episode that played.
                open(plan).onFailure { failure ->
                    if (plan.advancing) announceNextUnavailable(failure) else fail(failure)
                }
                return@transition
            }
            val current = _state.value
            val url = current.stream?.urls?.get(quality) ?: return@transition
            val at = current.positionMs
            flushProgressNow()
            lastReportedMs = at
            _state.update { it.copy(quality = quality, isBuffering = true, ready = false, error = null) }
            // No metadata: the session is already showing this episode, and a quality swap is
            // not a new thing to announce.
            engine.prepare(url, headers, at)
            engine.play()
        }
    }

    override suspend fun playNext() {
        transition { openNext() }.join()
    }

    override suspend fun switchEngine(engine: PlaybackEngine, carryPositionMs: Long) {
        val next = engine
        if (next === this.engine) return
        transition {
            // Awaited: the row this episode owns has to hold the position the old engine
            // reached before anything the new one reports can overwrite it.
            flushProgressNow()
            val previous = this.engine
            following?.cancelAndJoin()
            this.engine = next
            following = follow(next)
            previous.release()
            // Whether the sound is wanted down is this controller's to know, not an engine's: the
            // one that had the picture put its own volume back on release, and the one taking
            // over is told the current wish either way — before it prepares anything.
            next.duck(ducked)
            // A new engine gets its own budget for the one silent re-resolve. A cast session
            // can last hours, and the link that played locally is very likely stale by its end.
            reResolved = false
            lastReportedMs = carryPositionMs
            wasPlaying = false
            // Nothing is showing this. The screen that was is gone and the episode was living on
            // a receiver, so there is nobody to start it for: audio out of a phone in a pocket,
            // with no player, no notification and no media session to stop it with, is the one
            // outcome worse than stopping the television. The position is already on disk from
            // the flush above, so «Продолжить» picks the episode up whenever someone comes back.
            if (!screenAttached) {
                goIdle()
                return@transition
            }
            val current = _state.value
            val target = current.target
            val stream = current.stream
            val quality = current.quality
            val unfinished = opening
            _state.value = current.copy(
                isCasting = casting,
                isPlaying = false,
                // Nothing loaded is not "loading": a session that starts before the first
                // episode only decides where the next one will play.
                isBuffering = target != null || unfinished != null,
                ready = false,
                positionMs = carryPositionMs,
                error = null,
            )
            // An episode still being resolved when the engine changed was never handed to
            // anything, so there is nothing to carry: the new engine has to finish the job.
            // Giving up here would leave a screen buffering with no error to retry from and no
            // way back in, which is what a disconnect during autoplay's resolve used to do.
            //
            // `opening` is the authority on what to resolve, not the state: mid-track-change the
            // state still carries the old voice, and mid-autoplay it still carries the episode
            // that just finished. Its own start position is right for the same reasons. The
            // state is only fallen back on when a resolve already failed, where re-opening the
            // episode on screen is the retry the viewer would have pressed.
            //
            // An episode playing off the device is the other thing that has to be opened rather
            // than carried: its links are this phone's expired ones and its bytes are in a cache
            // no receiver can reach, so a television handed them would sit on a black screen with
            // nothing to report. The same resolve that would have started it there does it here.
            val handingOverADownload = playingDownload && casting
            // And the reverse, for the same reason read the other way. The link the receiver was
            // given is a fresh Kodik address whose query-stripped key is not the download's, so
            // carrying it back would re-stream an episode already on disk — and with the network
            // gone, which is often why a session ends, it would not play at all. The cost is one
            // index query on the way back; it resolves only when there is nothing to find.
            val returningToADownload = !casting && target != null &&
                downloads.completed(target.animeId, target.episode) != null
            val unopened = unfinished
                ?: target?.takeIf {
                    stream == null || quality == null || handingOverADownload || returningToADownload
                }?.let { Opening(it.copy(startPositionMs = carryPositionMs), freshEpisode = false, local = false) }
            if (unopened != null) {
                // A next episode that will not resolve is still a next episode: the viewer gets
                // the passing message and keeps the episode they were watching, not a red line
                // about an episode that played perfectly well.
                open(unopened).onFailure { failure ->
                    if (unopened.advancing) announceNextUnavailable(failure) else fail(failure)
                }
                return@transition
            }
            if (target == null || stream == null || quality == null) {
                opening = null
                return@transition
            }
            next.prepare(
                stream.urls.getValue(quality),
                headers,
                carryPositionMs,
                describe(target, stream, animeOf(target.animeId)),
            )
            next.play()
            onEngineState(next.state.value, force = true)
        }.join()
    }

    override fun cancelAutoplay() {
        autoplayCancelled = true
        _state.update { it.copy(autoplayCountdownSec = null) }
    }

    override suspend fun retry() {
        transition {
            val current = _state.value.target ?: return@transition
            reResolved = false
            // Asked afresh, not out of the cache the failure was answered from. An episode that
            // no voice had when the viewer pressed play may well be there by the time they press
            // «Повторить», and the catalogue lives six hours.
            resolve.forgetCatalogue(current.animeId)
            val plan = Opening(
                current.copy(startPositionMs = _state.value.positionMs),
                freshEpisode = false,
                preferQuality = _state.value.quality,
                // The same episode, opened again after a failure. Nobody needs to be switched to
                // what they are already watching.
                local = false,
            )
            opening = plan
            flushProgressNow()
            _state.update { it.copy(isBuffering = true, ready = false, error = null) }
            open(plan).onFailure(::fail)
        }.join()
    }

    override fun attachScreen() {
        screenAttached = true
    }

    override fun reportProgress() = flushProgress()

    override fun release() {
        // The screen is going away either way, and what it was showing decides the rest.
        screenAttached = false
        // A screen closing is not a reason to stop a television in another room. While a
        // receiver has the picture this writes the position down and lets go of nothing else:
        // the episode plays on, and coming back to the player finds the remote where it was.
        // «Отключить» — ending the session — is the control that stops it.
        if (casting) {
            flushProgress()
            return
        }
        transition?.cancel()
        transition = null
        flushProgress()
        engine.release()
        goIdle()
    }

    /**
     * Which playback the deferred-removal target belongs to.
     *
     * Only [goIdle] reports that target from a coroutine rather than inline, because `release()`
     * cannot suspend; everything else is already ordered by being on the one dispatcher this
     * controller runs on. The token is what keeps that one report from arriving late.
     */
    private var playbackGeneration = 0

    /** Forget what was playing. Which engine is live is the one thing that survives. */
    private fun goIdle() {
        // Nothing is reading anything now, so anything held back for that reason can go. On a
        // coroutine because `release()` cannot suspend, and therefore behind a token: a re-open
        // that begins before this is dispatched has already named its own episode, and nulling the
        // target out from under it is exactly what lets a mark delete a file mid-episode.
        val generation = ++playbackGeneration
        scope.launch {
            if (generation == playbackGeneration) deleteWatchedDownloads.nowPlaying(null, null)
        }
        opening = null
        _state.value = PlaybackState(isCasting = casting)
        markedEpisode = false
        marks = SkipMarks.NONE
        marksAsked = false
        endingSkipped = false
        lastSeenMs = -1
        settledAtMs = -1
        playingDownload = false
        reResolved = false
        autoplayCancelled = false
        lastReportedMs = -1
        wasPlaying = false
    }

    /**
     * Resolves [target] and hands it to the engine. Failures are returned rather than shown:
     * a failed next episode and a failed first episode mean different things to the viewer.
     */
    private suspend fun open(plan: Opening): Result<Unit> {
        val (target, freshEpisode, preferQuality) = plan
        // Re-asserted here so a caller that could not record it earlier — nothing suspends
        // between its decision and this call — is still covered.
        opening = plan
        settings = readSettings()
        val source = streamFor(target, plan).getOrElse {
            opening = null
            return Result.failure(it)
        }
        val stream = source.stream
        playingDownload = source.onDevice
        // Whether this stand-in is news. A link re-signed behind the viewer's back, or an engine
        // taking over the same episode, arrives at the same stand-in by the same road, and telling
        // the viewer a second time would be the app repeating itself.
        val before = _state.value
        val alreadyStandingIn = source.insteadOf != null &&
            before.target?.let { it.animeId == target.animeId && it.episode == target.episode } == true &&
            before.stream?.translation?.id == stream.translation.id &&
            before.insteadOf?.id == source.insteadOf.id
        // One read, two users: what the notification says this is, and how many episodes there
        // are to go. Read per episode rather than followed, so a catalogue refresh landing
        // mid-episode cannot move the goalposts of a countdown already under way.
        val anime = animeOf(target.animeId)
        val quality = preferQuality?.takeIf { stream.urls.containsKey(it) }
            ?: EpisodeQueue.startQuality(stream.urls.keys, settings.quality)
            ?: stream.urls.keys.first()
        if (freshEpisode) {
            markedEpisode = false
            reResolved = false
            autoplayCancelled = false
            // Whether the ending was stepped over belongs to the episode rather than to the file,
            // so it survives a swap and goes with the episode.
            endingSkipped = false
        }
        // The marks belong to the file, and another voice or another rung is another file with
        // another length — which is the one fact the whole feature is keyed on. Anything else
        // arriving here is the same file opened again: a re-signed link, or an engine taking the
        // episode over, and there is nothing new to ask.
        val sameFile = !freshEpisode &&
            before.stream?.translation?.id == stream.translation.id &&
            before.quality == quality
        if (!sameFile) {
            marks = SkipMarks.NONE
            marksAsked = false
        }
        lastReportedMs = target.startPositionMs
        lastSeenMs = -1
        settledAtMs = -1
        wasPlaying = false
        _state.value = PlaybackState(
            target = target,
            stream = stream,
            quality = quality,
            isBuffering = true,
            positionMs = target.startPositionMs,
            // A track swap keeps the length it already knows, so the timeline does not flash empty.
            durationMs = if (freshEpisode) 0 else _state.value.durationMs,
            airedEpisodes = anime?.availableEpisodes ?: 0,
            insteadOf = source.insteadOf,
            isCasting = casting,
        )
        engine.prepare(stream.urls.getValue(quality), headers, target.startPositionMs, describe(target, stream, anime))
        engine.play()
        if (source.insteadOf != null && !alreadyStandingIn) {
            _events.trySend(PlaybackEvent.TranslationSubstituted(source.insteadOf, stream.translation, target.episode))
        }
        // After the resolve, not before it: the voice is what a friend has to be told, and until
        // Kodik has answered nobody knows which one this is.
        if (plan.local) {
            _localActions.tryEmit(LocalAction.Episode(target.animeId, target.episode, stream.translation.id))
        }
        // What is being read now. «Удалять просмотренные» holds back any episode named here, and
        // lets go of the one this call moves off — the mark that asks for a deletion is raised at
        // nine tenths of an episode, while its file is still under the engine.
        playbackGeneration++
        deleteWatchedDownloads.nowPlaying(target.animeId, target.episode)
        // An episode is genuinely playing, which is the moment a title reached from search earns
        // its place in the list — before that there is no entry, and therefore no card anywhere to
        // find this half-watched episode by again. Here rather than at the first position written,
        // so a Cast prefetch or a resolve that came to nothing never adds anything.
        //
        // On the controller's scope and never awaited: creating a rate is a round trip, and no
        // frame of video waits on Shikimori. Idempotent, so a retry or a change of voice re-opening
        // this same episode costs one local read.
        scope.launch { addToList(target.animeId) }
        // Whatever the last playback was told not to count belongs to that playback. This one is
        // the viewer choosing to watch an episode, including when it is the same one.
        suppressedMarks.clear()
        opening = null
        // Everything the engine said while this transition ran was ignored on purpose. Take its
        // word now, or a player that reports nothing further would leave the screen mid-swap.
        onEngineState(engine.state.value, force = true)
        return Result.success(Unit)
    }

    /**
     * Where this episode comes from.
     *
     * An episode already on the device is a resolve that has already happened: the link was
     * signed, the segments are in the cache, and asking Kodik for it again would only spend a
     * network round trip to arrive at the same bytes — or fail, in the tunnel this feature
     * exists for. So a finished download wins, and nothing is asked of the source at all.
     *
     * Except when the viewer *chose* a different voice than the one on the device. That is a
     * request the download cannot answer, and refusing to resolve it would turn «сменить озвучку»
     * into a control that silently does nothing. With no network even that falls back to what is
     * there: one voice is what the device has, and it is better than a red line over an episode
     * that would play.
     *
     * Chose, not merely carries: [Opening.pickedTrack] is the difference. An episode picked off
     * the list and an episode autoplay ran into both arrive naming whatever voice was on screen,
     * and treating that as a choice would stream a file already on the device.
     *
     * All of it on the io dispatcher, including the reads: resolving reads a player page and
     * picks it apart, the download index is a database query, and asking the platform whether
     * there is a network is a binder call. Everything after this is main-thread work — the
     * state, the player and its surface all live there.
     */
    private suspend fun streamFor(target: PlaybackTarget, plan: Opening): Result<Source> {
        val pickedTrack = plan.pickedTrack
        // Read here, on the thread that owns it. A receiver fetches from the CDN itself and
        // cannot reach this phone's cache, so an episode on the device is an answer for the
        // engine on the device and for nothing else: casting always resolves, and offline —
        // where it cannot — it fails with the same reason as everything else.
        val onThisDevice = !casting
        return withContext(io) {
            val downloaded = if (onThisDevice) downloads.completedStream(target.animeId, target.episode) else null
            if (downloaded != null && !(pickedTrack && asksAnotherTrack(target, downloaded))) {
                return@withContext Result.success(Source(downloaded, onDevice = true))
            }
            // The resolve is attempted whatever the platform thinks of the network, and that is
            // deliberate. «Online» here means an interface that claims to carry the internet, and
            // on a connection the platform will not validate — a captive portal it cannot probe, a
            // network where its own check endpoints are blocked — that claim is wrong in the
            // direction that costs the most: refusing to ask Kodik means playing nothing at all on
            // a network that works. So the flag never gates the request; it only decides how a
            // failure is worded afterwards.
            resolve(target.animeId, target.episode, target.translation, substitute = plan.substitutable)
                .map { Source(it.stream, onDevice = false, insteadOf = it.insteadOf) }
                .recoverCatching { failure ->
                    val offline = !connectivity.online.first()
                    // One voice on the device beats a red line over an episode that would play.
                    if (offline && downloaded != null) return@recoverCatching Source(downloaded, onDevice = true)
                    throw if (offline && failure.isNetworkFailure()) {
                        SourceUnavailable(SourceUnavailableReason.OFFLINE, failure)
                    } else {
                        failure
                    }
                }
        }
    }

    /**
     * The failure looks like one a network would cause, rather than one Kodik answered with.
     *
     * Only these become «нет сети», and only when the device also says there is none. A source
     * that answered and said no is a different sentence, and telling a viewer to download the
     * episode in advance would be advice they cannot act on.
     */
    private fun Throwable.isNetworkFailure(): Boolean = this is NetworkUnavailable || this is IOException

    /** The voice the viewer chose is not the one the download was fetched in. */
    private fun asksAnotherTrack(target: PlaybackTarget, downloaded: EpisodeStream): Boolean =
        target.translation != null && target.translation.id != downloaded.translation.id

    private suspend fun openNext() {
        val current = _state.value.target ?: return
        // Moving on from inside the ending *is* finishing the episode, whether the viewer pressed
        // «Следующая серия» or the setting did it for them. Said here rather than at each caller
        // so every road to the next episode — the button, the automatic skip, the remote's own
        // next key — counts the one being left in the same way.
        countWatchedIfLeavingTheEnding()
        // The voice the viewer has, not the one that stood in for it: a stand-in was for one
        // episode, and the next is asked for in the chosen voice again — which may well have it.
        val track = _state.value.insteadOf ?: _state.value.stream?.translation ?: current.translation
        val plan = Opening(EpisodeQueue.next(current).copy(translation = track), freshEpisode = true, advancing = true)
        opening = plan
        // Awaited, not launched: resolving the next episode writes this anime's row itself, and
        // the position of the episode just finished has to be on disk before that happens.
        flushProgressNow()
        open(plan).onFailure(::announceNextUnavailable)
    }

    /**
     * The next episode could not be started. Whatever went wrong — an episode that has not
     * aired, a source that would not serve it, no connection — the countdown must not start over
     * on the next engine report, or an ended player would keep retrying for as long as it is
     * left alone. A passing message, not the error screen: the episode that just finished is
     * still there, and «Следующая серия» is the retry.
     */
    private fun announceNextUnavailable(failure: Throwable) {
        autoplayCancelled = true
        _state.update { it.copy(isBuffering = false, autoplayCountdownSec = null) }
        _events.trySend(PlaybackEvent.NextEpisodeUnavailable(failure))
    }

    /**
     * The anime from the local cache — the card is already there, because nothing reaches the
     * player without passing a screen that showed it. Null for one this device has never seen,
     * which costs the notification its title and the countdown its guard, but never the video.
     */
    private suspend fun animeOf(animeId: Int): Anime? =
        withContext(io) { library.observeAnimeDetails(animeId).first() }

    /** What the notification says. */
    private fun describe(target: PlaybackTarget, stream: EpisodeStream, anime: Anime?): StreamMetadata {
        return StreamMetadata(
            title = anime?.title ?: "${target.episode} серия",
            subtitle = listOfNotNull(
                anime?.let { "${target.episode} серия" },
                stream.translation.title,
            ).joinToString("   "),
            artworkUrl = anime?.posterUrl,
        )
    }

    private suspend fun readSettings() = Settings(
        threshold = prefs.watchedThreshold.first(),
        autoplay = prefs.autoplayNext.first(),
        quality = prefs.defaultQuality.first(),
        skipEnding = prefs.skipEnding.first(),
    )

    private fun onEngineState(engineState: EngineState, force: Boolean = false) {
        if (switching && !force) return
        val current = _state.value
        val target = current.target ?: return
        engineState.error?.let { return onEngineError(it, target) }

        // Between a prepare and the first parsed manifest the engine knows neither length nor
        // position. What the controller asked for is the better answer until it does, and the
        // decisions that depend on where the end is simply wait.
        val lengthKnown = engineState.durationMs > 0
        val duration = if (lengthKnown) engineState.durationMs else current.durationMs
        val position = if (lengthKnown) engineState.positionMs else current.positionMs
        val buffered = if (lengthKnown) engineState.bufferedPositionMs else current.bufferedPositionMs
        val ended = engineState.ended && lengthKnown
        val countdown = if (lengthKnown) countdownFor(position, duration, ended) else current.autoplayCountdownSec
        _state.value = current.copy(
            isPlaying = engineState.isPlaying,
            isBuffering = engineState.isBuffering,
            // Once known, known until the next prepare: a report with no length in it mid-episode
            // is the engine between two words, not an episode that has become unseekable.
            ready = current.ready || lengthKnown,
            positionMs = position,
            bufferedPositionMs = buffered,
            durationMs = duration,
            nextEpisodeDue =
                if (lengthKnown) EpisodeQueue.nextEpisodeDue(position, duration, ended) else current.nextEpisodeDue,
            autoplayCountdownSec = countdown,
            skip = if (lengthKnown) SkipRules.offer(marks, position, duration) else current.skip,
        )
        if (lengthKnown) {
            askForMarks(target, duration)
            reportIfDue(position, duration, paused = wasPlaying && !engineState.isPlaying)
            markIfWatched(position, duration)
            skipEndingIfDue(position, duration)
        }
        wasPlaying = engineState.isPlaying
        if (lengthKnown) lastSeenMs = position
        if (lengthKnown && countdown != null && countdown <= 0) advanceToNext()
    }

    /**
     * An expired link looks exactly like a broken one, and Kodik's links expire in hours.
     * So the first failure of an episode is answered by resolving it again from the same
     * position, without telling the viewer; only a second one is a failure worth showing.
     */
    private fun onEngineError(error: Throwable, target: PlaybackTarget) {
        if (reResolved) {
            // What was playing came off the device, so whatever media3 says broke, it was not the
            // network carrying it: the file was removed, or its bytes will not decode. «Нет сети.
            // Скачайте серию заранее» is the one sentence that points at the thing to do about it.
            if (playingDownload) {
                fail(SourceUnavailable(SourceUnavailableReason.OFFLINE, error), fromDownload = true)
            } else {
                fail(error)
            }
            return
        }
        reResolved = true
        val at = _state.value.positionMs
        val quality = _state.value.quality
        transition {
            _state.update { it.copy(isBuffering = true, ready = false, error = null) }
            // Not a local action: this is the same episode opened again behind the viewer's back,
            // and a friend watching along has no business being switched to what they are already
            // watching. Same reason `retry()` says so.
            open(
                Opening(
                    target.copy(startPositionMs = at),
                    freshEpisode = false,
                    preferQuality = quality,
                    local = false,
                ),
            ).onFailure(::fail)
        }
    }

    private fun countdownFor(positionMs: Long, durationMs: Long, ended: Boolean): Int? {
        if (!settings.autoplay || autoplayCancelled) return null
        // Nothing has aired after this one. The episode ends and stays ended: a countdown here
        // would run down to «Серия ещё не появилась в Kodik», which is the app answering a
        // question nobody asked.
        if (!_state.value.hasNextEpisode) return null
        if (!EpisodeQueue.countdownDue(positionMs, durationMs, ended)) return null
        if (ended || durationMs <= 0) return 0
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        return ceil(remaining / 1000.0).toInt().coerceIn(0, EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC)
    }

    private fun advanceToNext() {
        if (switching) return
        transition { openNext() }
    }

    /**
     * Asks once, as soon as there is a length to ask with.
     *
     * Here rather than at the resolve because the length is the question: AniSkip keeps intervals
     * against the file they were marked for, and until the engine has read the manifest nobody
     * knows which file is playing. Never awaited and never retried — an episode plays exactly the
     * same without an answer, and a source that is down must not be asked four times a second.
     */
    private fun askForMarks(target: PlaybackTarget, durationMs: Long) {
        if (marksAsked) return
        marksAsked = true
        scope.launch {
            val found = withContext(io) { skipMarks.marks(target.animeId, target.episode, durationMs) }
            // The episode can move on inside a request. Marks belong to the episode they were
            // asked about, and to no other.
            val live = _state.value.target ?: return@launch
            if (live.animeId != target.animeId || live.episode != target.episode) return@launch
            marks = SkipRules.accept(found, durationMs)
            // Shown at once rather than on the next report: an answer that lands four seconds
            // into a ninety-second opening still has most of its ten seconds to be useful in.
            _state.update { it.copy(skip = SkipRules.offer(marks, it.positionMs, it.durationMs)) }
        }
    }

    /**
     * Counts the episode as watched when what is being stepped over is its ending.
     *
     * Through the same path a natural finish takes — the threshold, the suppression, the
     * once-per-episode guard all still apply — by standing the position at the end of the
     * episode, which is where the viewer is going. An episode left from anywhere else is not
     * touched: pressing «Следующая серия» three minutes in is not finishing anything.
     */
    private fun countWatchedIfLeavingTheEnding() {
        val current = _state.value
        if (current.durationMs <= 0) return
        if (!SkipRules.endingSkipDue(marks, current.positionMs, current.durationMs) &&
            current.skip?.kind != SkipKind.ENDING
        ) {
            return
        }
        endingSkipped = true
        markIfWatched(current.durationMs, current.durationMs)
    }

    /**
     * «Пропускать эндинг»: ten seconds into the ending, step over the rest of it.
     *
     * The next episode where there is one, and out of the player where there is not — an episode
     * that ends a show should not leave the viewer watching credits they asked never to see. Once
     * per episode, because a position past the ten-second mark keeps arriving four times a second.
     */
    private fun skipEndingIfDue(positionMs: Long, durationMs: Long) {
        if (!settings.skipEnding || endingSkipped || switching) return
        if (!SkipRules.endingSkipDue(marks, positionMs, durationMs)) return
        // Playing into the ending, not landing in it. A position the bar was dragged to arrives
        // from somewhere outside the ending, and a second drag inside it arrives before playback
        // has carried a second past the seek — neither is the viewer asking to be moved on.
        if (positionMs < settledAtMs) return
        if (!SkipRules.insideEnding(marks, lastSeenMs, durationMs)) return
        val current = _state.value
        when {
            current.hasNextEpisode -> {
                endingSkipped = true
                transition { openNext() }
            }
            // The count is known and this was the last of them: the episode is finished here
            // rather than by the next one starting, and the screen is told there is nowhere left
            // to go. The picture stops with the decision, so credits nobody asked to see cannot
            // keep sounding over the way out — or outlive the screen in a floating window.
            current.airedEpisodes > 0 -> {
                endingSkipped = true
                markIfWatched(durationMs, durationMs)
                engine.pause()
                _events.trySend(PlaybackEvent.NothingLeftToPlay)
            }
            // No count at all: an anime this device has never cached, an announcement, an ongoing
            // show with nothing recorded as aired. «That was the last one» and «we do not know how
            // many there are» look exactly alike from here, and the ejection is only right about
            // one of them — so the ending plays out, which is what it does with the setting off.
            else -> Unit
        }
    }

    private fun reportIfDue(positionMs: Long, durationMs: Long, paused: Boolean) {
        val moved = lastReportedMs < 0 || abs(positionMs - lastReportedMs) >= EpisodeQueue.PROGRESS_INTERVAL_MS
        if (!paused && !moved) return
        val sample = sampleAt(positionMs, durationMs) ?: return
        lastReportedMs = positionMs
        launchWrite(sample)
    }

    private fun markIfWatched(positionMs: Long, durationMs: Long) {
        if (markedEpisode || !EpisodeQueue.watched(positionMs, durationMs, settings.threshold)) return
        val target = _state.value.target ?: return
        // The viewer said this episode is not watched while it was playing — from a title screen in
        // front of a cast session, or behind picture-in-picture. Counting it now would put the mark
        // back minutes later with nothing on screen to say so.
        if (suppressedMarks.isSuppressed(target.animeId, target.episode)) return
        markedEpisode = true
        scope.launch {
            markWatched(target.animeId, target.episode).onSuccess { outcome ->
                if (outcome.suggestCompleted) _events.trySend(PlaybackEvent.SuggestCompleted(target.animeId))
            }
        }
    }

    /**
     * The position of whatever is playing right now, written down without waiting. For callers
     * that cannot suspend — a screen going away, a release — where nothing is about to overwrite
     * the same row.
     *
     * On the controller's own scope, never a screen's: the last sample of a session is taken
     * exactly when that screen is going away.
     */
    private fun flushProgress() {
        val sample = currentSample() ?: return
        lastReportedMs = sample.positionMs
        launchWrite(sample)
    }

    /**
     * The same, but on disk before it returns. Every transition that resolves an episode uses
     * this one, because resolving remembers an episode against the very row this sample is for:
     * a write still on its way can land after it and put the row back on the episode that just
     * ended.
     */
    private suspend fun flushProgressNow() {
        val sample = currentSample()?.also { lastReportedMs = it.positionMs }
        sample?.let { write(it) }
        // Reporting only queues when another write is already draining, so the sample above may
        // still be waiting behind it. The drain that owns the queue is what has to finish.
        writing?.join()
    }

    /**
     * Hands [sample] to [WatchProgress] without waiting, and remembers the write that owns the
     * queue. Samples reported while that one is draining are coalesced into it rather than
     * queued up behind a slow disk, so the owner is the single thing worth joining.
     */
    private fun launchWrite(sample: Sample) {
        val job = scope.launch { write(sample) }
        if (writing?.isActive != true) writing = job
    }

    private fun currentSample(): Sample? = _state.value.let { sampleAt(it.positionMs, it.durationMs) }

    /**
     * What to write for the episode on screen, or null while there is nothing worth writing.
     *
     * A voice standing in is not written: [WatchProgress] reads a null track as «keep what is
     * remembered», and what is remembered is the voice the viewer chose, which the resolve
     * deliberately left in place. Writing the stand-in here would flip the row seconds after
     * the resolve declined to.
     */
    private fun sampleAt(positionMs: Long, durationMs: Long): Sample? {
        val current = _state.value
        val target = current.target ?: return null
        if (durationMs <= 0) return null
        val track = current.stream?.translation
        val trackId = if (current.insteadOf != null) null else track?.id
        return Sample(target.animeId, target.episode, positionMs, durationMs, trackId, track?.season)
    }

    private suspend fun write(sample: Sample) = progress.report(
        animeId = sample.animeId,
        episode = sample.episode,
        positionMs = sample.positionMs,
        durationMs = sample.durationMs,
        translationId = sample.translationId,
        kodikSeason = sample.season,
    )

    /**
     * @param fromDownload whether what failed was the copy on this device. False for everything
     *   that comes out of a resolve — including a resolve for an episode that happens to be
     *   downloaded — and true only where the engine was reading the download when it broke.
     */
    private fun fail(error: Throwable, fromDownload: Boolean = false) {
        _state.update {
            it.copy(isBuffering = false, isPlaying = false, error = error, failedReadingDownload = fromDownload)
        }
    }

    /**
     * Runs one resolve-and-prepare, replacing whichever was still running.
     *
     * Started lazily and only once it is on record: on the main dispatcher a launched
     * coroutine begins before `launch` returns, so assigning the job afterwards would leave
     * the first half of every transition looking like no transition at all — and engine
     * reports would be acted on in the middle of a swap.
     */
    private fun transition(block: suspend () -> Unit): Job {
        transition?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        transition = job
        job.start()
        return job
    }

    /**
     * Takes [engine] at its word from now on: what it reports and, if it renders anything, the
     * player a surface attaches to. One job, so handing over is one cancellation.
     */
    private fun follow(engine: PlaybackEngine): Job = scope.launch {
        launch { engine.state.collect { onEngineState(it) } }
        launch { engine.videoPlayer.collect { _videoPlayer.value = it } }
    }

    private companion object {
        /** Deep enough for the scrubbing of an impatient viewer; a session drains it at once. */
        const val ACTION_BUFFER = 32
    }
}
