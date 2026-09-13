package app.kaeru.ui.tv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.SkeletonGrid
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TvPosterCard
import app.kaeru.ui.common.library.LibraryUiState
import app.kaeru.ui.common.library.emptyTabCopy
import app.kaeru.ui.common.library.libraryCardSubtitle
import app.kaeru.ui.common.library.libraryTabs
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry

private const val FIND_ANIME = "Найти аниме"
private const val GRID_COLUMNS = 5
private const val SKELETON_CELLS = 10

/**
 * The viewer's own list, one status at a time.
 *
 * The six tabs are always all there, counts included, even the ones sitting at zero: hiding a tab
 * would make the row shorter and the list less honest, and «Отложено 0» answers the question an
 * absent tab leaves open.
 *
 * There is no sort control here. On a phone it is a chip a thumb reaches for; with a remote it
 * would be another stop on the way to the grid, and the order that matters on a television —
 * whatever moved most recently — is the one the list already opens in.
 */
@Composable
fun TvLibraryScreen(
    state: LibraryUiState,
    onStatus: (ListStatus) -> Unit,
    onAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val tabs = remember(state.counts) { libraryTabs(state.counts) }
    val selected = remember { FocusRequester() }
    LaunchedEffect(Unit) { selected.requestFocusOrLog("the open tab of the television library") }
    Column(
        modifier
            .fillMaxSize()
            .padding(start = TvLayout.Gutter, end = TvLayout.GutterEnd, top = TvLayout.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        LazyRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            items(tabs, key = { it.status.name }) { tab ->
                StatusPill(
                    text = tab.text,
                    selected = tab.status == state.status,
                    onClick = { onStatus(tab.status) },
                    affordance = false,
                    modifier = if (tab.status == state.status) Modifier.focusRequester(selected) else Modifier,
                )
            }
        }
        when {
            state.isLoading -> SkeletonGrid(count = SKELETON_CELLS, gutter = 0.dp, columns = GRID_COLUMNS)
            state.items.isEmpty() -> TvEmptyTab(state.status, onSearch)
            else -> TvLibraryGrid(state.items, state.watchedThreshold, onAnime)
        }
    }
}

@Composable
private fun TvLibraryGrid(items: List<LibraryEntry>, threshold: Float, onAnime: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(top = KaeruTokens.Space2, bottom = TvLayout.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        items(items, key = { it.anime.id }) { entry ->
            TvPosterCard(
                posterUrl = entry.anime.posterUrl,
                title = entry.anime.title,
                onClick = { onAnime(entry.anime.id) },
                modifier = Modifier.fillMaxWidth(),
                subtitle = libraryCardSubtitle(entry),
                progress = entry.progressFraction(threshold),
                // The cell decides how wide a card is here, not the design system's row pitch.
                width = Dp.Unspecified,
                titleMaxLines = 1,
            )
        }
    }
}

@Composable
private fun TvEmptyTab(status: ListStatus, onSearch: (() -> Unit)?) {
    val copy = emptyTabCopy(status)
    val offers = copy.offersSearch && onSearch != null
    EmptyState(
        title = copy.title,
        text = copy.text,
        modifier = Modifier.fillMaxSize(),
        actionLabel = FIND_ANIME.takeIf { offers },
        onAction = onSearch.takeIf { offers },
    )
}

private fun previewState(status: ListStatus = ListStatus.WATCHING, items: Int = 8) = LibraryUiState(
    items = (1..items).map { id ->
        tvPreviewEntry(tvPreviewAnime(id, previewTitles[id % previewTitles.size]), watched = id)
    },
    counts = mapOf(
        ListStatus.WATCHING to 12,
        ListStatus.PLANNED to 34,
        ListStatus.COMPLETED to 87,
    ),
    status = status,
    isLoading = false,
)

private val previewTitles = listOf(
    "Фрирен, провожающая в последний путь",
    "Дандадан",
    "Магическая битва",
    "Восхождение в тени",
    "Ванпанчмен",
    "Клинок, рассекающий демонов",
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvLibraryPreview() = KaeruTvTheme {
    TvLibraryScreen(state = previewState(), onStatus = {}, onAnime = {}, onSearch = {})
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvLibraryEmptyPreview() = KaeruTvTheme {
    TvLibraryScreen(
        state = previewState(status = ListStatus.PLANNED, items = 0),
        onStatus = {},
        onAnime = {},
        onSearch = {},
    )
}
