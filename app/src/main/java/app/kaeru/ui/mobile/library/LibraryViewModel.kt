package app.kaeru.ui.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

enum class LibrarySort { UPDATED, TITLE }

data class LibraryUiState(
    val items: List<LibraryEntry> = emptyList(),
    /** How many titles sit under each status, counted over the whole list rather than the open tab. */
    val counts: Map<ListStatus, Int> = emptyMap(),
    val status: ListStatus = ListStatus.WATCHING,
    val sort: LibrarySort = LibrarySort.UPDATED,
    /** True until the database has answered once, so an empty tab is never shown over an unread list. */
    val isLoading: Boolean = true,
    val watchedThreshold: Float = 0.9f,
)

/**
 * How titles are ordered by name.
 *
 * `String.compareTo` orders by UTF-16 code point, which puts `ё` (U+0451) after `я` (U+044F): a
 * list sorted that way hides «Ёлка» at the very bottom, past every other Russian name. A collator
 * is the only thing that gets this right, and at SECONDARY strength it also stops capitalisation
 * deciding the order while keeping `е` and `ё` apart.
 *
 * The locale is Russian rather than the device's: every title in this app is `nameRu`, so the
 * alphabet the list is read in does not change with the phone's language.
 *
 * One per sort rather than one for the app: a `Collator` carries mutable state and is not safe to
 * share across threads, and a list is sorted a handful of times an evening.
 */
private fun titleOrder(): Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply {
    strength = Collator.SECONDARY
}

/** How many titles sit under each status. Statuses nobody uses are simply absent from the map. */
fun libraryCounts(items: List<LibraryEntry>): Map<ListStatus, Int> =
    items.groupingBy { it.rate.status }.eachCount()

/**
 * One tab's worth of the list: the titles under [status], in the order [sort] asks for.
 *
 * Both orders end in the same tie-break by name. Shikimori stamps a whole page of titles with the
 * same update time when a list is imported, and without a second key those titles would come back
 * in whatever order the database happened to hand them over — a grid that reshuffles itself
 * between two visits looks broken even though nothing changed.
 */
fun selectLibrary(items: List<LibraryEntry>, status: ListStatus, sort: LibrarySort): List<LibraryEntry> {
    val filtered = items.filter { it.rate.status == status }
    val collator = titleOrder()
    val byName = compareBy<LibraryEntry> { collator.getCollationKey(it.anime.title) }
    return when (sort) {
        LibrarySort.UPDATED -> filtered.sortedWith(
            compareByDescending<LibraryEntry> { it.rate.updatedAt }.then(byName),
        )
        LibrarySort.TITLE -> filtered.sortedWith(byName)
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: LibraryRepository,
    prefs: PlaybackPreferences,
) : ViewModel() {
    private val status = MutableStateFlow(ListStatus.WATCHING)
    private val sort = MutableStateFlow(LibrarySort.UPDATED)

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeLibrary(),
        status,
        sort,
        prefs.watchedThreshold,
    ) { items, selected, order, threshold ->
        LibraryUiState(
            items = selectLibrary(items, selected, order),
            counts = libraryCounts(items),
            status = selected,
            sort = order,
            isLoading = false,
            watchedThreshold = threshold,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectStatus(value: ListStatus) { status.value = value }
    fun selectSort(value: LibrarySort) { sort.value = value }
}
