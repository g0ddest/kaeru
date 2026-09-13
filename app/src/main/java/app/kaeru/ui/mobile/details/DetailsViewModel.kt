package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.ui.common.errorMessageOrNull
import app.kaeru.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class DetailsUiState(
    val entry: LibraryEntry? = null,
    /** Card data for anime that is not (yet) in the user's list; equals `entry.anime` otherwise. */
    val anime: Anime? = null,
    val refreshing: Boolean = true,
    /** A write to the viewer's list is in flight, whichever control started it. */
    val updatingStatus: Boolean = false,
    val errorMessage: String? = null,
    /**
     * The dub whose save produced [errorMessage], so «Повторить» repeats that pick rather than
     * reloading the anime, which is not what failed.
     */
    val failedPick: Translation? = null,
    /** How much of an episode counts as watched; decides which episode the main button offers. */
    val watchedThreshold: Float = 0.9f,
    /** The dubs this anime has, ranked, once the chooser has asked for them. */
    val translations: List<Translation> = emptyList(),
    val loadingTranslations: Boolean = false,
    /** A dub is being written; the dub control says so and the chooser stops accepting taps. */
    val savingTranslation: Boolean = false,
    /** Why the dub list could not be read; shown inside the chooser, not over the screen. */
    val translationsError: String? = null,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val prefs: PlaybackPreferences,
    private val streams: ResolveEpisodeStream,
    private val watchStates: WatchStateRepository,
    private val markEpisodeWatched: MarkEpisodeWatched,
    private val clock: Clock,
) : ViewModel() {
    private val animeId: Int = checkNotNull(savedStateHandle["animeId"])
    private val work = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = combine(
        repository.observeAnime(animeId), repository.observeAnimeDetails(animeId), work, prefs.watchedThreshold,
    ) { entry, details, state, threshold ->
        state.copy(entry = entry, anime = entry?.anime ?: details, watchedThreshold = threshold)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            work.value = work.value.copy(refreshing = true, errorMessage = null, failedPick = null)
            val result = repository.refreshAnime(animeId)
            work.value = work.value.copy(refreshing = false, errorMessage = result.errorMessageOrNull())
        }
    }

    fun setStatus(status: ListStatus) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null, failedPick = null)
            val result = repository.setStatus(animeId, status)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.errorMessageOrNull())
        }
    }

    /**
     * Fetches the dubs on offer, the first time the chooser is opened.
     *
     * The catalogue call is a network round trip that most visits to this screen never need, so
     * nothing asks for it until the viewer opens the chooser. A list already in hand is not asked
     * for again; a list that failed is, because the retry inside the chooser calls this.
     */
    fun loadTranslations() {
        val current = work.value
        if (current.loadingTranslations || current.translations.isNotEmpty()) return
        viewModelScope.launch {
            work.value = work.value.copy(loadingTranslations = true, translationsError = null)
            val result = streams.translations(animeId)
            work.value = work.value.copy(
                loadingTranslations = false,
                translations = result.getOrDefault(emptyList()),
                translationsError = result.errorMessageOrNull(),
            )
        }
    }

    /**
     * Remembers a dub for this anime, so the next press of the watch button uses it.
     *
     * The memory rides on the same `WatchState` row that carries the playback position, so the
     * episode and the position already in it are preserved: changing the voice must not rewind the
     * show. When there is no row yet, one is started at the episode the watch button offers, at
     * position zero — which is where that press would start anyway.
     */
    fun pickTranslation(translation: Translation) {
        viewModelScope.launch {
            work.value = work.value.copy(savingTranslation = true, errorMessage = null, failedPick = null)
            val remembered = watchStates.observe(animeId).first()
            val row = remembered?.copy(
                translationId = translation.id,
                kodikSeason = translation.season,
                updatedAt = clock.instant(),
            ) ?: WatchState(
                animeId = animeId,
                episode = nextEpisode(),
                positionMs = 0,
                durationMs = 0,
                translationId = translation.id,
                kodikSeason = translation.season,
                updatedAt = clock.instant(),
            )
            val failure = save(row)
            work.value = work.value.copy(
                savingTranslation = false,
                errorMessage = failure,
                failedPick = translation.takeIf { failure != null },
            )
        }
    }

    /**
     * «Повторить» on the message the screen is showing.
     *
     * Not everything that lands in [DetailsUiState.errorMessage] is a failed load: a dub that
     * could not be written leaves one too, and reloading the anime would report success while
     * quietly leaving the dub unchanged. So the retry repeats whatever actually failed.
     */
    fun retry() {
        val pick = work.value.failedPick
        if (pick != null) pickTranslation(pick) else refresh()
    }

    /**
     * Counts an episode as watched without playing it.
     *
     * Shikimori owns that number, and [MarkEpisodeWatched] is the one place that raises it: it
     * picks a planned or shelved anime back up first, adds one that is in no list at all, and
     * sends nothing when the count is already high enough.
     */
    fun markWatched(episode: Int) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null, failedPick = null)
            val result = markEpisodeWatched(animeId, episode)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.errorMessageOrNull())
        }
    }

    /** The episode the watch button offers, read fresh rather than from the last composition. */
    private suspend fun nextEpisode(): Int =
        repository.observeAnime(animeId).first()?.nextEpisode(prefs.watchedThreshold.first()) ?: 1

    /**
     * Writes the row, and says what went wrong if it could not be written.
     *
     * [WatchStateRepository.save] signals by throwing — a logout mid-screen, a disk failure — and
     * a dub the app silently forgot is a dub the viewer picks again next episode, so the failure
     * is worth a line on the screen.
     */
    private suspend fun save(row: WatchState): String? = try {
        watchStates.save(row)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.toUserMessage()
    }
}
