package app.kaeru.ui.common.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.EpisodeQueue
import app.kaeru.player.PlaybackController
import app.kaeru.player.PlaybackEvent
import app.kaeru.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The player screen's brain, shared by the phone and the TV: the screens differ in how they
 * are driven, not in what they show.
 *
 * It owns nothing about playback itself — that lives in the process-wide
 * [PlaybackController], which is why a rotation, a notification or a trip to the home screen
 * do not interrupt anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val cast: CastSessionBridge,
    private val resolve: ResolveEpisodeStream,
    private val library: LibraryRepository,
    private val watchStates: WatchStateRepository,
    private val prefs: PlaybackPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** What only this screen knows: which sheet is open, and what is waiting to be said. */
    private data class ScreenState(
        val translations: List<Translation> = emptyList(),
        val loadingTranslations: Boolean = false,
        val sheet: PlayerSheet? = null,
        val completedPrompt: Boolean = false,
        val toast: String? = null,
    )

    private val animeId = MutableStateFlow<Int?>(null)
    private val screen = MutableStateFlow(ScreenState())
    private var requested: Pair<Int, Int>? = null
    private var startJob: Job? = null

    private val anime: Flow<Anime?> = animeId.flatMapLatest { id ->
        if (id == null) flowOf(null) else library.observeAnimeDetails(id)
    }

    /** The player a video surface attaches to, or null while there is none to attach to. */
    val videoPlayer: StateFlow<Player?> get() = controller.videoPlayer

    val uiState: StateFlow<PlayerUiState> = combine(
        controller.state,
        anime,
        screen,
        cast.receiverName,
    ) { playback, anime, screen, receiverName ->
        PlayerUiState(
            title = anime?.title.orEmpty(),
            posterUrl = anime?.posterUrl,
            episode = playback.target?.episode ?: 0,
            availableEpisodes = anime?.availableEpisodes ?: 0,
            translationTitle = playback.stream?.translation?.title,
            translationId = playback.stream?.translation?.id,
            isPlaying = playback.isPlaying,
            isBuffering = playback.isBuffering,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            quality = playback.quality,
            qualities = playback.stream?.urls?.keys.orEmpty().sortedBy { it.height },
            translations = screen.translations,
            loadingTranslations = screen.loadingTranslations,
            sheet = screen.sheet,
            nextEpisodeAvailable = playback.nextEpisodeAvailable,
            autoplayCountdownSec = playback.autoplayCountdownSec,
            errorMessage = playback.error?.toUserMessage(),
            isCasting = playback.isCasting,
            receiverName = receiverName,
            completedPrompt = screen.completedPrompt,
            toast = screen.toast,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is PlaybackEvent.SuggestCompleted -> screen.update { it.copy(completedPrompt = true) }
                    // One condition, one sentence: the same copy a failed episode would show.
                    is PlaybackEvent.NextEpisodeUnavailable ->
                        screen.update { it.copy(toast = event.error.toUserMessage()) }
                }
            }
        }
    }

    /**
     * Starts [episode] of [animeId], or does nothing if that is already what is playing —
     * the screen calls this every time it comes forward, and coming back from the background
     * must not rewind anything.
     *
     * A player with nothing loaded is started again even when it is the same episode: a screen
     * that was released while buried in the back stack would otherwise come back to black. A
     * start still on its way counts as loaded, so the two calls a screen makes on the way in —
     * one from the lifecycle, one from composition — are one playback.
     */
    fun start(animeId: Int, episode: Int) {
        // Said every time, including on the path that starts nothing: it is how playback left on
        // a receiver learns that somebody is looking at it again.
        controller.attachScreen()
        val loaded = controller.state.value.target
        // Already playing this very episode. That includes a receiver that kept going while the
        // screen was away, where starting again would interrupt a television for nothing — and a
        // screen recreated without its view model, which used to rewind to the last saved second.
        if (loaded?.animeId == animeId && loaded.episode == episode) {
            requested = animeId to episode
            this.animeId.value = animeId
            return
        }
        val same = requested == animeId to episode
        if (same && startJob?.isActive == true) return
        requested = animeId to episode
        this.animeId.value = animeId
        startJob = viewModelScope.launch {
            controller.play(PlaybackTarget(animeId, episode, resumeFrom(animeId, episode), translation = null))
        }
    }

    /**
     * Where to pick this episode up. A position belongs to the episode it was taken in, and
     * an episode already watched to its end starts over: resuming on the last frame would
     * only offer the next episode again.
     */
    private suspend fun resumeFrom(animeId: Int, episode: Int): Long {
        val saved = watchStates.observe(animeId).first()?.takeIf { it.episode == episode } ?: return 0
        val threshold = prefs.watchedThreshold.first()
        return if (EpisodeQueue.watched(saved.positionMs, saved.durationMs, threshold)) 0 else saved.positionMs
    }

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    /** Past the opening, roughly: one of the two buttons a viewer reaches for without looking. */
    fun skipIntro() = controller.seekBy(EpisodeQueue.SKIP_INTRO_MS)

    fun playNext() {
        viewModelScope.launch { controller.playNext() }
    }

    fun cancelAutoplay() = controller.cancelAutoplay()

    fun retry() {
        viewModelScope.launch { controller.retry() }
    }

    /** Loads the tracks on demand: the sheet is rarely opened and the list costs a request. */
    fun openTranslations() {
        val id = animeId.value ?: return
        screen.update { it.copy(loadingTranslations = true) }
        viewModelScope.launch {
            withContext(io) { resolve.translations(id) }
                .onSuccess { tracks ->
                    screen.update {
                        it.copy(translations = tracks, loadingTranslations = false, sheet = PlayerSheet.TRANSLATIONS)
                    }
                }
                .onFailure { failure ->
                    screen.update { it.copy(loadingTranslations = false, toast = failure.toUserMessage()) }
                }
        }
    }

    fun openQualities() = screen.update { it.copy(sheet = PlayerSheet.QUALITY) }

    fun closeSheet() = screen.update { it.copy(sheet = null) }

    fun pickTranslation(translation: Translation) {
        closeSheet()
        viewModelScope.launch { controller.changeTranslation(translation) }
    }

    fun pickQuality(quality: Quality) {
        closeSheet()
        controller.changeQuality(quality)
    }

    fun confirmCompleted() {
        val id = animeId.value ?: return
        screen.update { it.copy(completedPrompt = false) }
        viewModelScope.launch {
            library.setStatus(id, ListStatus.COMPLETED)
                .onFailure { failure -> screen.update { it.copy(toast = failure.toUserMessage()) } }
        }
    }

    fun dismissCompleted() = screen.update { it.copy(completedPrompt = false) }

    fun consumeToast() = screen.update { it.copy(toast = null) }

    /**
     * Disconnects from the receiver. Nothing is switched back here: ending the session is
     * announced by the framework, and the session bridge is the one that answers it, so the
     * same thing happens whether the viewer used this button or the system output switcher.
     */
    fun stopCasting() = cast.disconnect()

    /** The screen is going away for a moment: save where the viewer is, keep playing. */
    fun reportProgress() = controller.reportProgress()

    /**
     * The screen stopped and nothing is left to carry playback — no notification, no media
     * session, no receiver — so the picture must not go on playing under whatever replaced it.
     *
     * Only what is playing is stopped: toggling a paused episode would start one the viewer had
     * deliberately stopped. Nothing is released, so coming back and pressing play resumes the
     * episode where it left off rather than resolving it again.
     */
    fun pause() {
        if (controller.state.value.isPlaying) controller.togglePlayPause()
        controller.reportProgress()
    }

    /** The screen is closing for good. */
    fun release() = controller.release()
}
