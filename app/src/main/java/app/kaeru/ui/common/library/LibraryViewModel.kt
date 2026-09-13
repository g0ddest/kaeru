package app.kaeru.ui.common.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.text.CollationKey
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import kotlin.concurrent.getOrSet

enum class LibrarySort { UPDATED, TITLE }

/** What counts as watched when nobody has said otherwise, and what the list falls back to. */
private const val DEFAULT_THRESHOLD = 0.9f

data class LibraryUiState(
    val items: List<LibraryEntry> = emptyList(),
    /** How many titles sit under each status, counted over the whole list rather than the open tab. */
    val counts: Map<ListStatus, Int> = emptyMap(),
    val status: ListStatus = ListStatus.WATCHING,
    val sort: LibrarySort = LibrarySort.UPDATED,
    /** True until the database has answered once, so an empty tab is never shown over an unread list. */
    val isLoading: Boolean = true,
    val watchedThreshold: Float = DEFAULT_THRESHOLD,
)

/**
 * A library entry with the sort key for its name already built.
 *
 * Building it is the expensive half of ordering by name, and it depends on nothing but the title —
 * so it happens once, when a list arrives from the database, rather than twice per comparison
 * every time a tab or a sort order changes.
 */
data class SortedEntry(val entry: LibraryEntry, val nameKey: CollationKey)

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
 * One per thread, built once. A `Collator` is expensive to construct — it parses a rule table —
 * and carries mutable state while it works, so it can be neither rebuilt per sort nor shared
 * across the threads a flow may be collected on.
 */
private val TitleOrder = ThreadLocal<Collator>()

private fun titleOrder(): Collator = TitleOrder.getOrSet {
    Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
}

/** Builds each entry's name key once, keeping the list in the order it arrived. */
fun sortKeys(items: List<LibraryEntry>): List<SortedEntry> {
    val collator = titleOrder()
    return items.map { SortedEntry(it, collator.getCollationKey(it.anime.title)) }
}

/** How many titles sit under each status. Statuses nobody uses are simply absent from the map. */
fun libraryCounts(items: List<SortedEntry>): Map<ListStatus, Int> =
    items.groupingBy { it.entry.rate.status }.eachCount()

/**
 * One tab's worth of the list: the titles under [status], in the order [sort] asks for.
 *
 * Both orders end in the same tie-break by name. Shikimori stamps a whole page of titles with the
 * same update time when a list is imported, and without a second key those titles would come back
 * in whatever order the database happened to hand them over — a grid that reshuffles itself
 * between two visits looks broken even though nothing changed.
 */
fun selectLibrary(items: List<SortedEntry>, status: ListStatus, sort: LibrarySort): List<LibraryEntry> {
    val filtered = items.filter { it.entry.rate.status == status }
    val byName = compareBy<SortedEntry> { it.nameKey }
    val ordered = when (sort) {
        LibrarySort.UPDATED -> filtered.sortedWith(
            compareByDescending<SortedEntry> { it.entry.rate.updatedAt }.then(byName),
        )
        LibrarySort.TITLE -> filtered.sortedWith(byName)
    }
    return ordered.map { it.entry }
}

/**
 * The watched threshold, in a form that cannot hold the list up.
 *
 * The grid comes out of Room and the threshold out of DataStore, and `combine` waits for both. A
 * preferences read that is merely slow used to keep the skeletons on screen, and one that throws —
 * a corrupt preferences file is the realistic case — ended the combined flow and left them pulsing
 * for good. Starting on the default fixes the first and catching fixes the second; in both cases
 * the value the list falls back to is the one it would have read anyway.
 */
private fun PlaybackPreferences.threshold(): Flow<Float> = watchedThreshold
    .onStart { emit(DEFAULT_THRESHOLD) }
    .catch { emit(DEFAULT_THRESHOLD) }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: LibraryRepository,
    prefs: PlaybackPreferences,
    // The app's one off-the-main-thread dispatcher. Sorting is processor work rather than IO, but
    // a second pool for one sort would cost more than it saves, and a test hands in its own.
    @IoDispatcher background: CoroutineDispatcher,
) : ViewModel() {
    private val status = MutableStateFlow(ListStatus.WATCHING)
    private val sort = MutableStateFlow(LibrarySort.UPDATED)

    val uiState: StateFlow<LibraryUiState> = combine(
        // Keyed where the list changes, not where it is read: a tab switch re-sorts, it does not
        // re-key.
        repository.observeLibrary().map(::sortKeys),
        status,
        sort,
        prefs.threshold(),
    ) { items, selected, order, threshold ->
        LibraryUiState(
            items = selectLibrary(items, selected, order),
            counts = libraryCounts(items),
            status = selected,
            sort = order,
            isLoading = false,
            watchedThreshold = threshold,
        )
    }
        // Keying and sorting a few hundred titles is not main-thread work, and it runs again on
        // every database emission, tab switch and sort switch.
        .flowOn(background)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectStatus(value: ListStatus) { status.value = value }
    fun selectSort(value: LibrarySort) { sort.value = value }
}
