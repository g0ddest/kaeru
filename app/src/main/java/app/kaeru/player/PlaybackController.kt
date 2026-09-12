package app.kaeru.player

import androidx.media3.common.Player
import app.kaeru.data.library.AppPreferences
import app.kaeru.di.IoDispatcher
import app.kaeru.di.PlaybackScope
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** Things that are announced once: see [PlaybackEvent]. */
    val events: SharedFlow<PlaybackEvent>

    /** The Media3 player a video surface attaches to. */
    val videoPlayer: Player?

    /** Resolves [target], points the engine at it and starts playing. Suspends until playback is under way. */
    suspend fun play(target: PlaybackTarget)

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    /** Same episode, same position, another voice. */
    suspend fun changeTranslation(translation: Translation)

    /** Same episode, same position, another rung of the quality ladder. */
    fun changeQuality(quality: Quality)

    /** The episode after this one, in the same track. Announces [PlaybackEvent.NextEpisodeMissing] if there is none. */
    suspend fun playNext()

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
 */
@Singleton
class DefaultPlaybackController @Inject constructor(
    private val engine: PlaybackEngine,
    private val resolve: ResolveEpisodeStream,
    private val progress: WatchProgress,
    private val markWatched: MarkEpisodeWatched,
    private val library: LibraryRepository,
    private val prefs: AppPreferences,
    private val headers: StreamHeaders,
    @param:PlaybackScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackController {

    /** Settings are read once per episode: changing them mid-episode should not move the goalposts. */
    private data class Settings(
        val threshold: Float = 0.9f,
        val autoplay: Boolean = true,
        val quality: Quality? = null,
    )

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    override val videoPlayer: Player? get() = engine.videoPlayer

    private var settings = Settings()

    /** The one resolve-and-prepare at a time; while it runs, engine reports are not acted on. */
    private var transition: Job? = null
    private val switching: Boolean get() = transition?.isActive == true

    private var markedEpisode = false
    private var reResolved = false
    private var autoplayCancelled = false
    private var lastReportedMs = -1L
    private var wasPlaying = false

    init {
        scope.launch { engine.state.collect { onEngineState(it) } }
    }

    override suspend fun play(target: PlaybackTarget) {
        transition {
            flushProgress()
            _state.value = PlaybackState(target = target, isBuffering = true, positionMs = target.startPositionMs)
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
            flushProgress()
            _state.update { it.copy(isBuffering = true, error = null) }
            val sameEpisode = current.copy(startPositionMs = _state.value.positionMs, translation = translation)
            open(sameEpisode, freshEpisode = false, preferQuality = _state.value.quality).onFailure(::fail)
        }.join()
    }

    override fun changeQuality(quality: Quality) {
        val current = _state.value
        val url = current.stream?.urls?.get(quality) ?: return
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

    override fun cancelAutoplay() {
        autoplayCancelled = true
        _state.update { it.copy(autoplayCountdownSec = null) }
    }

    override suspend fun retry() {
        transition {
            val current = _state.value.target ?: return@transition
            reResolved = false
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
        _state.value = PlaybackState()
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
        flushProgress()
        val next = EpisodeQueue.next(current).copy(translation = track)
        open(next, freshEpisode = true).onFailure { failure ->
            if (failure is EpisodeNotAvailable) {
                // Not a playback failure: the show simply has not got there yet. The finished
                // episode stays on screen, and the countdown does not start over on the next tick.
                autoplayCancelled = true
                _state.update { it.copy(autoplayCountdownSec = null) }
                _events.tryEmit(PlaybackEvent.NextEpisodeMissing)
            } else {
                fail(failure)
            }
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
        writeProgress(positionMs, durationMs)
    }

    private fun markIfWatched(positionMs: Long, durationMs: Long) {
        if (markedEpisode || !EpisodeQueue.watched(positionMs, durationMs, settings.threshold)) return
        val target = _state.value.target ?: return
        markedEpisode = true
        scope.launch {
            markWatched(target.animeId, target.episode).onSuccess { outcome ->
                if (outcome.suggestCompleted) _events.tryEmit(PlaybackEvent.SuggestCompleted(target.animeId))
            }
        }
    }

    /** The position of whatever is playing right now, written down. Silent when there is nothing to say. */
    private fun flushProgress() {
        val current = _state.value
        if (current.durationMs <= 0) return
        writeProgress(current.positionMs, current.durationMs)
    }

    private fun writeProgress(positionMs: Long, durationMs: Long) {
        val current = _state.value
        val target = current.target ?: return
        lastReportedMs = positionMs
        val track = current.stream?.translation
        // On the controller's own scope, never a screen's: the last sample of a session is
        // taken exactly when that screen is going away.
        scope.launch {
            progress.report(
                animeId = target.animeId,
                episode = target.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                translationId = track?.id,
                kodikSeason = track?.season,
            )
        }
    }

    private fun fail(error: Throwable) {
        _state.update { it.copy(isBuffering = false, isPlaying = false, error = error) }
    }

    /** Runs one resolve-and-prepare, replacing whichever was still running. */
    private fun transition(block: suspend () -> Unit): Job {
        transition?.cancel()
        return scope.launch { block() }.also { transition = it }
    }
}
