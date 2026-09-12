package app.kaeru.ui.mobile.library

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
import javax.inject.Inject

enum class LibrarySort { UPDATED, TITLE }

data class LibraryUiState(
    val items: List<LibraryEntry> = emptyList(),
    val status: ListStatus = ListStatus.WATCHING,
    val sort: LibrarySort = LibrarySort.UPDATED,
)

fun selectLibrary(items: List<LibraryEntry>, status: ListStatus, sort: LibrarySort): List<LibraryEntry> {
    val filtered = items.filter { it.rate.status == status }
    return when (sort) {
        LibrarySort.UPDATED -> filtered.sortedByDescending { it.rate.updatedAt }
        LibrarySort.TITLE -> filtered.sortedBy { it.anime.title.lowercase() }
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(repository: LibraryRepository) : ViewModel() {
    private val status = MutableStateFlow(ListStatus.WATCHING)
    private val sort = MutableStateFlow(LibrarySort.UPDATED)
    val uiState: StateFlow<LibraryUiState> = combine(repository.observeLibrary(), status, sort) { items, selected, order ->
        LibraryUiState(selectLibrary(items, selected, order), selected, order)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectStatus(value: ListStatus) { status.value = value }
    fun selectSort(value: LibrarySort) { sort.value = value }
}
