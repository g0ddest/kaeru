package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
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
    val refreshing: Boolean = true,
    val updatingStatus: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val animeId: Int = checkNotNull(savedStateHandle["animeId"])
    private val work = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = combine(repository.observeAnime(animeId), work) { entry, state ->
        state.copy(entry = entry)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            work.value = work.value.copy(refreshing = true, errorMessage = null)
            val result = repository.refreshAnime(animeId)
            work.value = work.value.copy(refreshing = false, errorMessage = result.exceptionOrNull()?.message)
        }
    }

    fun setStatus(status: ListStatus) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null)
            val result = repository.setStatus(animeId, status)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.exceptionOrNull()?.message)
        }
    }
}
