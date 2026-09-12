package app.kaeru.player

import androidx.media3.common.Player
import app.kaeru.di.IoDispatcher
import app.kaeru.di.LocalEngine
import app.kaeru.di.PlaybackScope
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Resolves [target], points the engine at it and starts playing. Suspends until playback is under way. */
    suspend fun play(target: PlaybackTarget)

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

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
    private val progress: WatchProgress,
    private val markWatched: MarkEpisodeWatched,
    private val library: LibraryRepository,
    private val prefs: PlaybackPreferences,
    private val headers: StreamHeaders,
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

    /** Settings are read once per episode: changing them mid-episode should not move the goalposts. */
    private data class Settings(
        val threshold: Float = 0.9f,
        val autoplay: Boolean = true,
        val quality: Quality? = null,
    )

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = Channel<PlaybackEvent>(Channel.BUFFERED)
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private val _videoPlayer = MutableStateFlow<Player?>(null)
    override val videoPlayer: StateFlow<Player?> = _videoPlayer.asStateFlow()

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
    private var reResolved = false
    private var autoplayCancelled = false
    private var lastReportedMs = -1L
    private var wasPlaying = false

    /** The progress write that owns [WatchProgress]'s queue, or the last one that did. */
    private var writing: Job? = null

    // Last, not as a property initializer: following an engine starts reading its reports, and
    // on the main dispatcher that happens at once — before the fields those reports touch exist.
    init {
        following = follow(localEngine)
    }

    override suspend fun play(target: PlaybackTarget) {
        transition {
            flushProgressNow()
            _state.value = PlaybackState(
                target = target,
                isBuffering = true,
                positionMs = target.startPositionMs,
                isCasting = casting,
            )
            open(target, freshEpisode = true).onFailure(::fail)
        }.join()
    }

    override fun togglePlayPause() {
        val current = _state.value
        if (current.isPlaying) {
            engine.pause()
            return
        }
        // Pressing play on an episode that ran out should replay it, not sit on the last frame.
        if (current.durationMs > 0 && current.positionMs >= current.durationMs) engine.seekTo(0)
        engine.play()
    }

    override fun seekTo(positionMs: Long) {
        val clamped = EpisodeQueue.clampSeek(positionMs, _state.value.durationMs)
        _state.update { it.copy(positionMs = clamped) }
        engine.seekTo(clamped)
    }

    override fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override suspend fun changeTranslation(translation: Translation) {
        transition {
            val current = _state.value.target ?: return@transition
            flushProgressNow()
            _state.update { it.copy(isBuffering = true, error = null) }
            val sameEpisode = current.copy(startPositionMs = _state.value.positionMs, translation = translation)
            open(sameEpisode, freshEpisode = false, preferQuality = _state.value.quality).onFailure(::fail)
        }.join()
    }

    override fun changeQuality(quality: Quality) {
        val current = _state.value
        val url = current.stream?.urls?.get(quality) ?: return
        flushProgress()
        val at = current.positionMs
        lastReportedMs = at
        _state.value = current.copy(quality = quality, isBuffering = true, error = null)
        // No metadata: the session is already showing this episode, and a quality swap is
        // not a new thing to announce.
        engine.prepare(url, headers, at)
        engine.play()
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
            // A new engine gets its own budget for the one silent re-resolve. A cast session
            // can last hours, and the link that played locally is very likely stale by its end.
            reResolved = false
            lastReportedMs = carryPositionMs
            wasPlaying = false
            val current = _state.value
            val target = current.target
            val stream = current.stream
            val quality = current.quality
            _state.value = current.copy(
                isCasting = casting,
                isPlaying = false,
                // Nothing loaded is not "loading": a session that starts before the first
                // episode only decides where the next one will play.
                isBuffering = target != null,
                positionMs = carryPositionMs,
                error = null,
            )
            if (target == null || stream == null || quality == null) return@transition
            next.prepare(stream.urls.getValue(quality), headers, carryPositionMs, describe(target, stream))
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
            flushProgressNow()
            _state.update { it.copy(isBuffering = true, error = null) }
            open(
                current.copy(startPositionMs = _state.value.positionMs),
                freshEpisode = false,
                preferQuality = _state.value.quality,
            ).onFailure(::fail)
        }.join()
    }

    override fun reportProgress() = flushProgress()

    override fun release() {
        transition?.cancel()
        transition = null
        flushProgress()
        engine.release()
        // The receiver is still the receiver: the session outlives this screen, so the next
        // episode played from anywhere in the app goes where the viewer put the last one.
        _state.value = PlaybackState(isCasting = casting)
        markedEpisode = false
        reResolved = false
        autoplayCancelled = false
        lastReportedMs = -1
        wasPlaying = false
    }

    /**
     * Resolves [target] and hands it to the engine. Failures are returned rather than shown:
     * a failed next episode and a failed first episode mean different things to the viewer.
     */
    private suspend fun open(
        target: PlaybackTarget,
        freshEpisode: Boolean,
        preferQuality: Quality? = null,
    ): Result<Unit> {
        settings = readSettings()
        // Resolving reads a player page and picks it apart. That is not main-thread work, and
        // everything after it is: the state, the player and its surface all live there.
        val stream = withContext(io) { resolve(target.animeId, target.episode, target.translation) }
            .getOrElse { return Result.failure(it) }
        val quality = preferQuality?.takeIf { stream.urls.containsKey(it) }
            ?: EpisodeQueue.startQuality(stream.urls.keys, settings.quality)
            ?: stream.urls.keys.first()
        if (freshEpisode) {
            markedEpisode = false
            reResolved = false
            autoplayCancelled = false
        }
        lastReportedMs = target.startPositionMs
        wasPlaying = false
        _state.value = PlaybackState(
            target = target,
            stream = stream,
            quality = quality,
            isBuffering = true,
            positionMs = target.startPositionMs,
            // A track swap keeps the length it already knows, so the timeline does not flash empty.
            durationMs = if (freshEpisode) 0 else _state.value.durationMs,
            isCasting = casting,
        )
        engine.prepare(stream.urls.getValue(quality), headers, target.startPositionMs, describe(target, stream))
        engine.play()
        // Everything the engine said while this transition ran was ignored on purpose. Take its
        // word now, or a player that reports nothing further would leave the screen mid-swap.
        onEngineState(engine.state.value, force = true)
        return Result.success(Unit)
    }

    private suspend fun openNext() {
        val current = _state.value.target ?: return
        val track = _state.value.stream?.translation ?: current.translation
        // Awaited, not launched: resolving the next episode writes this anime's row itself, and
        // the position of the episode just finished has to be on disk before that happens.
        flushProgressNow()
        val next = EpisodeQueue.next(current).copy(translation = track)
        open(next, freshEpisode = true).onFailure { failure ->
            // Whatever went wrong — an episode that has not aired, a source that would not serve
            // it, no connection — the countdown must not start over on the next engine report, or
            // an ended player would keep retrying for as long as it is left alone.
            autoplayCancelled = true
            _state.update { it.copy(autoplayCountdownSec = null) }
            // A passing message, not the error screen: the episode that just finished is still
            // there and still playable, and «Следующая серия» is the retry.
            _events.trySend(PlaybackEvent.NextEpisodeUnavailable(failure))
        }
    }

    /**
     * What the notification says. The anime is read from the local cache — the card is already
     * there, because nothing reaches the player without passing a screen that showed it.
     */
    private suspend fun describe(target: PlaybackTarget, stream: EpisodeStream): StreamMetadata {
        val anime = withContext(io) { library.observeAnimeDetails(target.animeId).first() }
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
        val ended = engineState.ended && lengthKnown
        val countdown = if (lengthKnown) countdownFor(position, duration, ended) else current.autoplayCountdownSec
        _state.value = current.copy(
            isPlaying = engineState.isPlaying,
            isBuffering = engineState.isBuffering,
            positionMs = position,
            durationMs = duration,
            nextEpisodeAvailable =
                if (lengthKnown) EpisodeQueue.nextEpisodeDue(position, duration, ended) else current.nextEpisodeAvailable,
            autoplayCountdownSec = countdown,
        )
        if (lengthKnown) {
            reportIfDue(position, duration, paused = wasPlaying && !engineState.isPlaying)
            markIfWatched(position, duration)
        }
        wasPlaying = engineState.isPlaying
        if (lengthKnown && countdown != null && countdown <= 0) advanceToNext()
    }

    /**
     * An expired link looks exactly like a broken one, and Kodik's links expire in hours.
     * So the first failure of an episode is answered by resolving it again from the same
     * position, without telling the viewer; only a second one is a failure worth showing.
     */
    private fun onEngineError(error: Throwable, target: PlaybackTarget) {
        if (reResolved) {
            fail(error)
            return
        }
        reResolved = true
        val at = _state.value.positionMs
        val quality = _state.value.quality
        transition {
            _state.update { it.copy(isBuffering = true, error = null) }
            open(target.copy(startPositionMs = at), freshEpisode = false, preferQuality = quality)
                .onFailure(::fail)
        }
    }

    private fun countdownFor(positionMs: Long, durationMs: Long, ended: Boolean): Int? {
        if (!settings.autoplay || autoplayCancelled) return null
        if (!EpisodeQueue.countdownDue(positionMs, durationMs, ended)) return null
        if (ended || durationMs <= 0) return 0
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        return ceil(remaining / 1000.0).toInt().coerceIn(0, EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC)
    }

    private fun advanceToNext() {
        if (switching) return
        transition { openNext() }
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

    /** What to write for the episode on screen, or null while there is nothing worth writing. */
    private fun sampleAt(positionMs: Long, durationMs: Long): Sample? {
        val current = _state.value
        val target = current.target ?: return null
        if (durationMs <= 0) return null
        val track = current.stream?.translation
        return Sample(target.animeId, target.episode, positionMs, durationMs, track?.id, track?.season)
    }

    private suspend fun write(sample: Sample) = progress.report(
        animeId = sample.animeId,
        episode = sample.episode,
        positionMs = sample.positionMs,
        durationMs = sample.durationMs,
        translationId = sample.translationId,
        kodikSeason = sample.season,
    )

    private fun fail(error: Throwable) {
        _state.update { it.copy(isBuffering = false, isPlaying = false, error = error) }
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
}
