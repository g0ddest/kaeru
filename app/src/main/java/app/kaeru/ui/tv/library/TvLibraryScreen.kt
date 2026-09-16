package app.kaeru.ui.tv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import app.kaeru.ui.tv.TvFocusKey
import app.kaeru.ui.tv.TvFocusMemory
import app.kaeru.ui.tv.TvFocusRow
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.claimFocus
import app.kaeru.ui.tv.rememberTvFocusMemory
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry
import app.kaeru.ui.tv.tvRestoreTarget
import app.kaeru.ui.tv.tvRowScroll

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
    gridState: LazyGridState = rememberLazyGridState(),
    focus: TvFocusMemory = rememberTvFocusMemory(),
) {
    val tabs = remember(state.counts) { libraryTabs(state.counts) }
    val selected = remember { FocusRequester() }
    // The grid is one row as far as «where was I» is concerned, and the row is keyed on the open
    // tab: a card remembered in «Смотрю» must not drag focus into the grid when the viewer steps
    // across to «В планах».
    val focusRows = remember(state.status, state.items) {
        listOf(TvFocusRow(state.status.name, state.items.map { it.anime.id }))
    }
    // Null on a first visit, and only then. There the tab row is where the remote should start —
    // it is the top of the screen and the thing a viewer came here to change — while a viewer
    // coming back from a title card is owed the cell they opened it from.
    val restore = remember(focusRows) { focus.key?.let { tvRestoreTarget(it, focusRows) } }
    val claimed = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (restore == null) selected.requestFocusOrLog("the open tab of the television library")
    }
    // Taken to the remembered cell before anything asks for focus: a grid composes about two rows
    // of five, so a cell further down than that is not a node any request can name.
    //
    // Only while nothing has focus yet. `restore` is rebuilt on every change to the items, not only
    // on the way back from a title card, so without the latch a status write landing from Room
    // would scroll the grid out from under a remote that was being used.
    LaunchedEffect(restore, claimed.value) {
        val at = restore?.takeUnless { claimed.value } ?: return@LaunchedEffect
        tvRowScroll(at.index, gridState.firstVisibleItemIndex, TvLayout.GridViewport)
            ?.let { gridState.scrollToItem(it) }
    }
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
            else -> TvLibraryGrid(
                items = state.items,
                threshold = state.watchedThreshold,
                row = state.status.name,
                gridState = gridState,
                restoreId = restore?.id,
                claimed = claimed,
                focus = focus,
                onAnime = onAnime,
            )
        }
    }
}

@Composable
private fun TvLibraryGrid(
    items: List<LibraryEntry>,
    threshold: Float,
    row: String,
    gridState: LazyGridState,
    restoreId: Int?,
    claimed: MutableState<Boolean>,
    focus: TvFocusMemory,
    onAnime: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        // The safe area is held outside the scrolling viewport rather than being content padding
        // inside it: a focused cell is brought into the viewport, and a viewport that ran to the
        // bottom of the panel would bring it flush against the five per cent a television crops.
        modifier = Modifier.padding(bottom = TvLayout.SafeVertical),
        contentPadding = PaddingValues(top = KaeruTokens.Space2),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        items(items, key = { it.anime.id }) { entry ->
            val id = entry.anime.id
            val requester = remember { FocusRequester() }
            // The same rule as the home screen's cards: the cell asks, and the claim is latched by
            // the focus arriving rather than by the asking, so a request that lost the race against
            // placement is made again when the cell is composed instead of blocking every later one.
            LaunchedEffect(restoreId == id, claimed.value) {
                if (restoreId == id && !claimed.value) {
                    requester.claimFocus("card $id of the television library")
                }
            }
            TvPosterCard(
                posterUrl = entry.anime.posterUrl,
                title = entry.anime.title,
                onClick = { onAnime(id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focus.key = TvFocusKey(row, id)
                            claimed.value = true
                        }
                    },
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
