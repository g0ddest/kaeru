package app.kaeru.ui.common.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.EpisodeQueue
import app.kaeru.player.PlaybackController
import app.kaeru.player.PlaybackEvent
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.details.episodeCells
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
        val translations: List<RankedTranslation> = emptyList(),
        val loadingTranslations: Boolean = false,
        val sheet: PlayerSheet? = null,
        val completedPrompt: Boolean = false,
        val toast: String? = null,
    )

    /** The anime, and the season as the remote control lists it. Read together, shown together. */
    private data class Shown(val anime: Anime? = null, val episodes: List<EpisodeCell> = emptyList())

    private val animeId = MutableStateFlow<Int?>(null)
    private val screen = MutableStateFlow(ScreenState())
    private var requested: Pair<Int, Int>? = null
    private var startJob: Job? = null

    /**
     * Everything about the show itself. The season list is built here rather than on the screen
     * because it needs three sources — the catalogue, the viewer's count and this device's own
     * position — and a screen that gathered them would be the third place in the app doing it.
     */
    private val shown: Flow<Shown> = animeId.flatMapLatest { id ->
        if (id == null) {
            flowOf(Shown())
        } else {
            combine(
                library.observeAnimeDetails(id),
                library.observeAnime(id),
                watchStates.observe(id),
                prefs.watchedThreshold,
            ) { details, entry, watch, threshold ->
                val anime = entry?.anime ?: details
                Shown(anime, anime?.let { episodeCells(it, entry?.rate, watch, threshold) }.orEmpty())
            }
        }
    }

    /** The player a video surface attaches to, or null while there is none to attach to. */
    val videoPlayer: StateFlow<Player?> get() = controller.videoPlayer

    val uiState: StateFlow<PlayerUiState> = combine(
        controller.state,
        shown,
        screen,
        cast.receiverName,
        prefs.defaultQuality,
    ) { playback, shown, screen, receiverName, settledQuality ->
        val anime = shown.anime
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
            bufferedPositionMs = playback.bufferedPositionMs,
            durationMs = playback.durationMs,
            quality = playback.quality,
            qualities = playback.stream?.urls?.keys.orEmpty().sortedBy { it.height },
            rememberQuality = settledQuality != null,
            translations = screen.translations,
            loadingTranslations = screen.loadingTranslations,
            sheet = screen.sheet,
            nextEpisodeAvailable = playback.hasNextEpisode,
            episodeEnding = playback.nextEpisodeDue,
            nextEpisodeAt = anime?.nextEpisodeAt,
            moreEpisodesComing = anime != null && anime.status != AnimeStatus.RELEASED,
            episodes = shown.episodes,
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
     * Starts [episode] of [animeId], or attaches to the session already under way.
     *
     * The screen calls this every time it comes forward, and what it is allowed to do depends on
     * why it came forward. [explicit] is true only when the viewer chose an episode — a press on
     * a watch button, a tap on an episode tile — and false when the same screen is merely coming
     * back into view: the app minimised and reopened, a relaunch out of recents, an activity
     * rebuilt from its own saved state.
     *
     * That distinction is the whole of this method. The episode a screen was *opened* with goes
     * stale the moment autoplay moves on, and a screen that came back an hour and three episodes
     * later used to hand that stale number to the controller — rewinding a viewer on episode
     * seven to episode six at 0:00, or interrupting a television to do it. So a launch that is
     * not a choice never overrules what is playing: if anything at all is loaded for this anime,
     * that is the episode, at the position it is actually at.
     *
     * A player with nothing loaded is started even on that path — a screen released while buried
     * in the back stack would otherwise come back to black, and the intent is then the only thing
     * that knows what to play. A start still on its way counts as loaded, so the two calls a
     * screen makes on the way in — one from the lifecycle, one from composition — are one
     * playback.
     */
    fun start(animeId: Int, episode: Int, explicit: Boolean = true) {
        // Said every time, including on the path that starts nothing: it is how playback left on
        // a receiver learns that somebody is looking at it again.
        controller.attachScreen()
        val loaded = controller.state.value.target
        val live = loaded != null && loaded.animeId == animeId
        // Attach rather than start: either this is the very episode asked for, or it is not a
        // choice at all and whatever the session reached outranks the number the intent carries.
        if (live && (!explicit || loaded.episode == episode)) {
            requested = animeId to loaded.episode
            this.animeId.value = animeId
            return
        }
        val same = requested == animeId to episode
        if (same && startJob?.isActive == true) return
        requested = animeId to episode
        this.animeId.value = animeId
        startJob = viewModelScope.launch {
            val saved = watchStates.observe(animeId).first()
            // Nothing is loaded — the process was killed while the app was away — and the intent
            // is a photograph of the episode this screen was first opened with. This anime's own
            // row is not: autoplay writes it as it goes. So on a launch that is not a choice the
            // row wins, and the intent is only the answer when there is no row at all.
            val wanted = if (explicit) episode else saved?.episode ?: episode
            controller.play(PlaybackTarget(animeId, wanted, resumeFrom(saved, wanted), translation = null))
        }
    }

    /**
     * The screen was opened without an episode: the cast notification names none, because the
     * Cast framework builds that intent itself.
     *
     * Whatever is playing is what the viewer tapped the notification about, so it is adopted
     * whole — including the anime id, which everything the remote control draws from the
     * catalogue needs and which the intent does not carry. Nothing is started: a notification
     * only exists while something is already playing.
     */
    fun attachLive() {
        val live = controller.state.value.target ?: return
        controller.attachScreen()
        requested = live.animeId to live.episode
        animeId.value = live.animeId
    }

    /**
     * Where to pick this episode up, out of the row already read. A position belongs to the
     * episode it was taken in, and an episode already watched to its end starts over: resuming on
     * the last frame would only offer the next episode again.
     */
    private suspend fun resumeFrom(saved: WatchState?, episode: Int): Long {
        val row = saved?.takeIf { it.episode == episode } ?: return 0
        val threshold = prefs.watchedThreshold.first()
        return if (EpisodeQueue.watched(row.positionMs, row.durationMs, threshold)) 0 else row.positionMs
    }

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    /** Past the opening, roughly: one of the two buttons a viewer reaches for without looking. */
    fun skipIntro() = controller.seekBy(EpisodeQueue.SKIP_INTRO_MS)

    fun playNext() {
        viewModelScope.launch { controller.playNext() }
    }

    /**
     * The viewer picked an episode from the list on the remote control.
     *
     * The track that is playing carries over, exactly as it does when one episode runs into the
     * next: jumping back to episode two is not a request to reconsider the voice. Picking the
     * episode already on screen does nothing, rather than restarting a television mid-scene.
     */
    fun playEpisode(episode: Int) {
        val id = animeId.value ?: return
        if (controller.state.value.target?.episode == episode) return
        val track = controller.state.value.stream?.translation
        requested = id to episode
        startJob = viewModelScope.launch {
            val saved = watchStates.observe(id).first()
            controller.play(PlaybackTarget(id, episode, resumeFrom(saved, episode), translation = track))
        }
    }

    fun cancelAutoplay() = controller.cancelAutoplay()

    fun retry() {
        viewModelScope.launch { controller.retry() }
    }

    /** Loads the tracks on demand: the sheet is rarely opened and the list costs a request. */
    fun openTranslations() {
        val id = animeId.value ?: return
        val playing = controller.state.value.stream?.translation
        screen.update { it.copy(loadingTranslations = true) }
        viewModelScope.launch {
            withContext(io) { resolve.translations(id, playing) }
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
        // While the viewer has settled on a quality, every pick is a change of mind about which
        // one — not a one-off that leaves the old setting behind to override the next episode.
        if (uiState.value.rememberQuality) viewModelScope.launch { prefs.setDefaultQuality(quality) }
    }

    /**
     * Settles on the quality that is playing, or hands the choice back to the source.
     *
     * The switch lives in the quality chooser rather than in settings because that is where the
     * viewer is when they find out their connection will not carry 1080p. Turning it on takes the
     * rung they are on now; turning it off leaves this episode alone and lets the next one open
     * at the best the source offers.
     */
    fun setRememberQuality(on: Boolean) {
        val settled = if (on) controller.state.value.quality else null
        viewModelScope.launch { prefs.setDefaultQuality(settled) }
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
