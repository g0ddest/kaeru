package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailsUiState(
    val entry: LibraryEntry? = null,
    /** Card data for anime that is not (yet) in the user's list; equals `entry.anime` otherwise. */
    val anime: Anime? = null,
    val refreshing: Boolean = true,
    val updatingStatus: Boolean = false,
    val errorMessage: String? = null,
    /** How much of an episode counts as watched; decides which episode the main button offers. */
    val watchedThreshold: Float = 0.9f,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    prefs: PlaybackPreferences,
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
            work.value = work.value.copy(refreshing = true, errorMessage = null)
            val result = repository.refreshAnime(animeId)
            work.value = work.value.copy(refreshing = false, errorMessage = result.errorMessageOrNull())
        }
    }

    fun setStatus(status: ListStatus) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null)
            val result = repository.setStatus(animeId, status)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.errorMessageOrNull())
        }
    }
}
