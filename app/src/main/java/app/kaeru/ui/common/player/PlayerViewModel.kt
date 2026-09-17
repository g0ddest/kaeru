package app.kaeru.ui.common.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.EpisodeProgressRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** How long «Удалить загрузку» waits for the engine to let go of the file before retrying anyway. */
private const val REMOVAL_TIMEOUT_MS = 5_000L

/** One line naming both voices: the one that did not have the episode, and the one that does. */
private fun substitutedCopy(event: PlaybackEvent.TranslationSubstituted): String =
    "В озвучке ${event.askedFor.title} серии ${event.episode} нет — включена ${event.playing.title}"

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
    private val episodeProgress: EpisodeProgressRepository,
    private val prefs: PlaybackPreferences,
    private val downloads: DownloadRepository,
    private val connectivity: Connectivity,
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

    /**
     * Everything around the episode rather than about it: where the picture is going, what the
     * viewer settled on, whether there is a network at all, and what of this title is already on
     * the device.
     *
     * Gathered into one value because none of the four depends on which episode is playing, and
     * because `combine` is typed up to five flows — the episode itself already spends four of them.
     */
    private data class Surroundings(
        val receiverName: String? = null,
        val settledQuality: Quality? = null,
        val offline: Boolean = false,
        /**
         * The title [downloads] are about. Carried rather than read off the screen's own field,
         * because the controller is process-wide: between a screen naming its title and playback
         * reaching it, the episode on the controller still belongs to the title before it.
         */
        val animeId: Int? = null,
        val downloads: List<EpisodeDownload> = emptyList(),
    )

    private val animeId = MutableStateFlow<Int?>(null)
    private val screen = MutableStateFlow(ScreenState())
    private var requested: Pair<Int, Int>? = null
    private var startJob: Job? = null

    /**
     * Everything about the show itself. The season list is built here rather than on the screen
     * because it needs four sources — the catalogue, the viewer's count, this device's positions
     * and the episode it played last — and a screen that gathered them would be the third place in
     * the app doing it.
     *
     * The positions are read from their own repository rather than off [LibraryRepository]'s
     * entry, because this screen also opens on a title that is in no list at all, where there is
     * no entry to read them from.
     */
    private val shown: Flow<Shown> = animeId.flatMapLatest { id ->
        if (id == null) {
            flowOf(Shown())
        } else {
            combine(
                library.observeAnimeDetails(id),
                library.observeAnime(id),
                watchStates.observe(id),
                episodeProgress.observe(id),
                prefs.watchedThreshold,
            ) { details, entry, watch, progress, threshold ->
                val anime = entry?.anime ?: details
                Shown(anime, anime?.let { episodeCells(it, entry?.rate, watch, progress, threshold) }.orEmpty())
            }
        }
    }

    /**
     * The downloads of this title, so the player can say whether the episode on screen is on the
     * device — and follow one that is arriving while it plays.
     */
    private val surroundings: Flow<Surroundings> = combine(
        cast.receiverName,
        prefs.defaultQuality,
        // Registering a network callback is three binder calls, and they must not be made on the
        // thread drawing the player.
        connectivity.online.flowOn(io),
        animeId.flatMapLatest { id ->
            if (id == null) flowOf(null to emptyList()) else downloads.observe(id).map { id to it }
        },
    ) { receiverName, settledQuality, online, downloaded ->
        Surroundings(receiverName, settledQuality, !online, downloaded.first, downloaded.second)
    }

    /** The player a video surface attaches to, or null while there is none to attach to. */
    val videoPlayer: StateFlow<Player?> get() = controller.videoPlayer

    val uiState: StateFlow<PlayerUiState> = combine(
        controller.state,
        shown,
        screen,
        surroundings,
    ) { playback, shown, screen, around ->
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
            rememberQuality = around.settledQuality != null,
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
            episodeUnavailable = (playback.error as? EpisodeNotAvailable)?.reason,
            // Straight through from the controller, which is the only layer that knows whether the
            // file or the source was what broke.
            failedReadingDownload = playback.failedReadingDownload,
            isCasting = playback.isCasting,
            receiverName = around.receiverName,
            completedPrompt = screen.completedPrompt,
            offline = around.offline,
            download = playback.target
                ?.takeIf { it.animeId == around.animeId }
                ?.let { live -> around.downloads.firstOrNull { it.episode == live.episode } },
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
                    is PlaybackEvent.TranslationSubstituted ->
                        screen.update { it.copy(toast = substitutedCopy(event)) }
                }
            }
        }
        // What the voices say about the episode is about *this* episode, and the episode moves
        // under them: autoplay runs into the next one, and the television picks one from the
        // strip beside them. That television asks for the list once a session and leaves it up,
        // so a list left as it was captions the episode it was built for — «нет серии 4» over a
        // fifth the voice does have — and refuses the press as well.
        viewModelScope.launch {
            controller.state
                .map { it.target }
                .distinctUntilChanged { was, now -> was?.animeId == now?.animeId && was?.episode == now?.episode }
                .collect { target -> refreshTranslations(target) }
        }
    }

    /**
     * Says again what the voices already on screen carry, for the episode now on it.
     *
     * Nothing is fetched: the answer comes from what the source already holds — the catalogue it
     * keeps for six hours, and the pages it has read — so following the picture costs nothing.
     *
     * Only ever while a list is up. A screen that never opened the voices asks for nothing, and a
     * list that does not come back leaves the one on screen exactly as it was: nobody asked for
     * this, so there is nothing to tell the viewer about it going wrong.
     */
    private suspend fun refreshTranslations(target: PlaybackTarget?) {
        val id = animeId.value ?: return
        if (target == null || target.animeId != id || screen.value.translations.isEmpty()) return
        val playing = controller.state.value.stream?.translation
        withContext(io) { resolve.translations(id, playing, target.episode) }
            .onSuccess { tracks ->
                screen.update { if (it.translations.isEmpty()) it else it.copy(translations = tracks) }
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
            controller.play(PlaybackTarget(animeId, wanted, resumeFrom(saved, animeId, wanted), translation = null))
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
     *
     * @return whether there was a session to adopt. False leaves the screen with nothing to show
     *   and nothing to start, which the caller answers by closing it rather than by drawing a
     *   remote control with no receiver behind it.
     */
    fun attachLive(): Boolean {
        val live = controller.state.value.target ?: return false
        controller.attachScreen()
        requested = live.animeId to live.episode
        animeId.value = live.animeId
        return true
    }

    /**
     * Where to pick this episode up. Every episode keeps its own position, so going back to an
     * earlier one lands where that one was left rather than at the beginning — and, just as
     * importantly, never spends the position of the episode that was playing.
     *
     * An episode already watched to its end starts over: resuming on the last frame would only
     * offer the next episode again.
     *
     * A position the watch button would not offer is not one to drop the viewer into either: an
     * episode holding nothing but a mis-tap starts from the beginning, by the same cutoff the rest
     * of the feature uses.
     *
     * [saved] stands in when the per-episode table has no row for this episode but the anime's
     * pointer is inside it. The sampler writes the two independently, so either can be the one
     * that got through; the pointer is only ever believed about the episode it names.
     */
    private suspend fun resumeFrom(saved: WatchState?, animeId: Int, episode: Int): Long {
        val row = episodeProgress.observe(animeId).first().firstOrNull { it.episode == episode }
            ?: saved?.takeIf { it.episode == episode }
                ?.let { EpisodeProgress(animeId, episode, it.positionMs, it.durationMs, it.updatedAt) }
            ?: return 0
        if (!row.started) return 0
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
        val live = controller.state.value
        if (live.target?.episode == episode) return
        // The voice the viewer has, not one that stood in for it on this episode.
        val track = live.insteadOf ?: live.stream?.translation
        requested = id to episode
        startJob = viewModelScope.launch {
            val saved = watchStates.observe(id).first()
            controller.play(PlaybackTarget(id, episode, resumeFrom(saved, id, episode), translation = track))
        }
    }

    fun cancelAutoplay() = controller.cancelAutoplay()

    fun retry() {
        viewModelScope.launch { controller.retry() }
    }

    /**
     * Keeps the episode on screen on the device.
     *
     * No height is named, so the download settings decide it. The rung the viewer is watching at
     * is not an instruction about storage: someone who set «720p» for downloads did not ask for
     * 1080p by having one episode open at it, and the phone they are saving space on is the same
     * phone either way.
     */
    fun download() {
        val id = animeId.value ?: return
        val episode = liveEpisodeOf(id) ?: return
        viewModelScope.launch {
            downloads.enqueue(id, episode)
                .onFailure { failure -> screen.update { it.copy(toast = failure.toUserMessage()) } }
        }
    }

    /** Gives the space back. The episode plays from Kodik again, for as long as there is a network. */
    fun removeDownload() {
        val id = animeId.value ?: return
        val episode = liveEpisodeOf(id) ?: return
        viewModelScope.launch { downloads.remove(id, episode) }
    }

    /**
     * The episode playing right now, but only while it is an episode of [id].
     *
     * The controller serves the whole process, so what it holds during the moment between this
     * screen naming its title and playback reaching it is the *previous* title's episode. Pairing
     * the two would download episode seven of a show the viewer never opened.
     */
    private fun liveEpisodeOf(id: Int): Int? =
        controller.state.value.target?.takeIf { it.animeId == id }?.episode

    /**
     * «Удалить загрузку» on the failure over the video: takes the broken copy away, then starts the
     * episode again from the source.
     *
     * One action rather than the two it looks like, and strictly in that order. Opening an episode
     * prefers a finished download, so a retry that ran before the removal had landed would pick the
     * same unplayable file up again and fail in exactly the same way.
     */
    fun removeDownloadAndRetry() {
        val id = animeId.value ?: return
        val episode = liveEpisodeOf(id) ?: return
        viewModelScope.launch {
            if (!downloads.remove(id, episode)) {
                // The command never reached the engine — refused the same way a foreground add
                // can be. The row is still finished either way, so there is nothing to wait for:
                // waiting out the timeout would only delay a retry that was always going to open
                // the same file again.
                controller.retry()
                return@launch
            }
            // Waited for, not assumed. Removing only *sends* the request — the engine's service
            // picks it up later — while opening an episode reads the download index, so a retry
            // fired on the next line would find the row still finished, re-open the very file the
            // viewer asked to be rid of, and fail in the same way. Bounded, because a retry that
            // never happens is worse than one that re-opens a stale row.
            withTimeoutOrNull(REMOVAL_TIMEOUT_MS) {
                downloads.observe(id).first { rows ->
                    rows.none { it.episode == episode && it.state == DownloadState.COMPLETED }
                }
            }
            controller.retry()
        }
    }

    /** Loads the tracks on demand and shows them: the sheet costs a request to fill. */
    fun openTranslations() = fetchTranslations(show = true)

    /**
     * The same list, with nothing opened over the picture.
     *
     * The television has no sheet: the voices are a strip that is already on the panel, and all
     * it needs is for the list to arrive. Separate from [openTranslations] rather than a flag on
     * it, because the two screens are asking different things — «show me the voices» and «fill
     * the row I am already showing».
     */
    fun loadTranslations() = fetchTranslations(show = false)

    private fun fetchTranslations(show: Boolean) {
        val id = animeId.value ?: return
        val live = controller.state.value
        val playing = live.stream?.translation
        // The episode on screen, so each voice can say whether it has it. Only while the
        // controller is on this title: between naming it and playback reaching it, the episode
        // it holds belongs to the title before.
        val episode = live.target?.takeIf { it.animeId == id }?.episode
        screen.update { it.copy(loadingTranslations = true) }
        viewModelScope.launch {
            withContext(io) { resolve.translations(id, playing, episode) }
                .onSuccess { tracks ->
                    screen.update {
                        it.copy(
                            translations = tracks,
                            loadingTranslations = false,
                            sheet = if (show) PlayerSheet.TRANSLATIONS else it.sheet,
                        )
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
