package app.kaeru.ui.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.PosterCard
import app.kaeru.ui.common.design.SkeletonGrid
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.library.LibrarySort
import app.kaeru.ui.common.library.emptyTabCopy
import app.kaeru.ui.common.library.LibraryUiState
import app.kaeru.ui.common.library.libraryCardSubtitle
import app.kaeru.ui.common.library.libraryTabs
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private const val TITLE = "Мой список"
private const val FIND_ANIME = "Найти аниме"
private const val SORT_UPDATED = "Обновление"
private const val SORT_TITLE = "Название"

/** A constant of the screen, not of a composition. */
private val SortOptions = listOf(LibrarySort.UPDATED to SORT_UPDATED, LibrarySort.TITLE to SORT_TITLE)

/** Two full rows of the loading grid, which fits the shortest phone the app supports. */
private const val SKELETON_CELLS = 6

/**
 * Narrow enough that three columns survive a 320dp phone, wide enough that a fifth appears on a
 * tablet: `(288 + 12) / (88 + 12)` is exactly 3 at the narrowest screen the app supports.
 */
private val GridMinCell = 88.dp

/** Room under the last row so the bottom bar never sits on a poster. */
private val GridBottom = KaeruTokens.Space8

/**
 * Everything the viewer has already decided to watch, in six tabs.
 *
 * The tabs are the screen: which one is open decides what the grid holds, and the amber on the
 * open one is the same amber the bottom bar uses for the open tab — in this app the accent always
 * means «this is the one you are looking at» or «this is the thing to press». The sort control
 * beneath is deliberately not amber: a second accent on the same screenful would make the viewer
 * hunt for which of the two the colour was pointing at.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onStatus: (ListStatus) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onAnime: (Int) -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(TITLE)
        StatusTabs(state.counts, state.status, onStatus)
        SortControl(state.sort, onSort)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // Two rows, not a screenful: `SkeletonGrid` is a plain Column with nothing to
                // scroll, so a count that outgrows this box gets its last row sliced off.
                state.isLoading -> SkeletonGrid(Modifier.padding(top = KaeruTokens.Space4), count = SKELETON_CELLS)
                state.items.isEmpty() -> EmptyTab(state.status, onSearch)
                else -> LibraryGrid(state.items, state.watchedThreshold, onAnime)
            }
        }
    }
}

/** Where the list is cut, and how much is in each part. */
@Composable
private fun StatusTabs(counts: Map<ListStatus, Int>, selected: ListStatus, onStatus: (ListStatus) -> Unit) {
    val tabs = remember(counts) { libraryTabs(counts) }
    LazyRow(
        // The pills carry `Role.Tab` one at a time; the group is what lets a screen reader say
        // which of how many, the way Material's own tab row does.
        modifier = Modifier.padding(top = KaeruTokens.Space2).selectableGroup(),
        contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        items(tabs, key = { it.status.name }) { tab ->
            StatusPill(
                text = tab.text,
                selected = tab.status == selected,
                onClick = { onStatus(tab.status) },
                // Nothing opens; the grid under it changes. The default chevron would promise a menu.
                affordance = false,
            )
        }
    }
}

/**
 * Two ways to read the same tab: what moved last, or the alphabet.
 *
 * Material's segmented row is kept for its shape and its roving behaviour and restyled onto the
 * palette: the chosen half takes the elevated surface and full-strength text, the other stays on
 * the background in secondary. That is enough of a difference to read at a glance without spending
 * the accent, which on this screen belongs to the open tab above.
 */
@Composable
private fun SortControl(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .padding(start = KaeruTokens.GutterPhone, top = KaeruTokens.Space3)
            .height(KaeruTokens.MinTouchTarget),
    ) {
        SortOptions.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = sort == option,
                onClick = { onSort(option) },
                shape = SegmentedButtonDefaults.itemShape(index, SortOptions.size, KaeruTokens.ButtonShape),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = KaeruElevated,
                    activeContentColor = KaeruText,
                    activeBorderColor = KaeruDivider,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = KaeruSecondary,
                    inactiveBorderColor = KaeruDivider,
                ),
                // The container already says which half is chosen; Material's tick only shoves the
                // label sideways when the choice changes.
                icon = {},
                label = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            )
        }
    }
}

/** The list itself: artwork, name, and how far through it the viewer is. */
@Composable
private fun LibraryGrid(items: List<LibraryEntry>, threshold: Float, onAnime: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GridMinCell),
        contentPadding = PaddingValues(
            start = KaeruTokens.GutterPhone,
            end = KaeruTokens.GutterPhone,
            top = KaeruTokens.Space4,
            bottom = GridBottom,
        ),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        items(items, key = { it.anime.id }) { entry ->
            PosterCard(
                posterUrl = entry.anime.posterUrl,
                title = entry.anime.title,
                onClick = { onAnime(entry.anime.id) },
                modifier = Modifier.fillMaxWidth(),
                subtitle = libraryCardSubtitle(entry),
                progress = entry.progressFraction(threshold),
                // The cell decides how wide a card is here, not the design system's row pitch.
                width = Dp.Unspecified,
                // Two lines reserved, so «7 из 28» lines up across a row of cards.
                titleMinLines = 2,
            )
        }
    }
}

/**
 * A tab with nothing under it, which for two of the six is a moment to point somewhere.
 *
 * «Смотрю» and «В планах» are the tabs a viewer fills on purpose, so both offer the way to fill
 * them. The other four fill themselves as a consequence of watching, and a button on «Брошено»
 * would be an invitation to go and abandon something.
 */
@Composable
private fun EmptyTab(status: ListStatus, onSearch: () -> Unit) {
    val copy = emptyTabCopy(status)
    EmptyState(
        title = copy.title,
        text = copy.text,
        modifier = Modifier.fillMaxSize(),
        actionLabel = FIND_ANIME.takeIf { copy.offersSearch },
        onAction = onSearch.takeIf { copy.offersSearch },
    )
}
