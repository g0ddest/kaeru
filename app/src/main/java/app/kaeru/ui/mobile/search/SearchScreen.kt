package app.kaeru.ui.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Anime
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.PosterCard
import app.kaeru.ui.common.design.SearchField
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.SkeletonGrid
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.mobile.KaeruSnackbarHost
import app.kaeru.ui.mobile.RetrySnackbar

private const val TITLE = "Поиск"
private const val IDLE_TITLE = "Что посмотреть сегодня?"
private const val IDLE_TEXT =
    "Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."
private const val NOT_FOUND_TITLE = "Ничего не найдено"
private const val NOT_FOUND_TEXT = "Попробуйте оригинальное название или короче."

/** Same arithmetic as the library grid: three columns survive a 320dp phone, five fit a tablet. */
private val GridMinCell = 88.dp

/** Two full rows of the loading grid, which fits the shortest phone the app supports. */
private const val SKELETON_CELLS = 6

/**
 * Where a title that is not in the list yet gets found.
 *
 * One field, the queries already typed, and the answers. The field carries no button: «Найти»
 * inside a Material text field is what the viewer saw sitting on the outline, and the keyboard's
 * own search key was doing the same job all along. Nothing is written under the field either — a
 * failed search takes the grid, because there is nothing else on the screen to keep.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onRecent: (String) -> Unit,
    onPlanned: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val failure = state.addFailure
    // A write that failed over results the viewer can still use, so it is a message rather than a
    // screen, and its «Повторить» goes back to the same title.
    //
    // Keyed on the failure's own number, not on its words: two titles failing offline produce the
    // same sentence, and `RetrySnackbar`'s own `LaunchedEffect(message)` would treat the second one
    // as the first still showing.
    key(failure?.event) {
        RetrySnackbar(failure?.message, snackbar) { failure?.let { onPlanned(it.animeId) } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            KaeruTopBar(TITLE)
            SearchField(
                query = state.query,
                onQueryChange = onQuery,
                onSubmit = onSubmit,
                modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
                enabled = !state.searching,
            )
            if (state.recentQueries.isNotEmpty()) {
                RecentQueries(state.recentQueries, onRecent)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val content = searchContentState(state)) {
                    // Two rows, not a screenful: `SkeletonGrid` is a plain Column with nothing
                    // to scroll, so a count that outgrows this box gets its last row sliced off.
                    SearchContent.Loading -> SkeletonGrid(Modifier.padding(top = KaeruTokens.Space4), count = SKELETON_CELLS)
                    SearchContent.Results ->
                        // Only the three fields the grid draws, so a keystroke in the field above
                        // leaves its item provider alone.
                        ResultGrid(state.results, state.libraryIds, state.addingAnimeId, onPlanned, onOpen)
                    SearchContent.Idle -> EmptyState(IDLE_TITLE, IDLE_TEXT, Modifier.fillMaxSize())
                    SearchContent.NotFound -> EmptyState(NOT_FOUND_TITLE, NOT_FOUND_TEXT, Modifier.fillMaxSize())
                    is SearchContent.Error -> ErrorState(content.message, onRetry, Modifier.fillMaxSize())
                }
            }
        }
        KaeruSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(KaeruTokens.Space4))
    }
}

/**
 * What was searched for before, newest first.
 *
 * Chips rather than a list: five past queries as a row cost one line of the screen, and the row is
 * the fastest thing on it — one press runs the search again without the keyboard opening at all.
 * None of them is «selected», so none is amber; they are buttons, and announce themselves as such.
 */
@Composable
private fun RecentQueries(queries: List<String>, onRecent: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = KaeruTokens.Space1),
        contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        items(queries, key = { it }) { query ->
            StatusPill(
                text = query,
                selected = false,
                onClick = { onRecent(query) },
                role = Role.Button,
                // Nothing opens behind it; the chip is the whole action.
                affordance = false,
            )
        }
    }
}

/** The answers, and the one thing worth doing with one without opening it. */
@Composable
private fun ResultGrid(
    results: List<Anime>,
    libraryIds: Set<Int>,
    adding: Int?,
    onPlanned: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GridMinCell),
        contentPadding = PaddingValues(
            start = KaeruTokens.GutterPhone,
            end = KaeruTokens.GutterPhone,
            top = KaeruTokens.Space4,
            bottom = KaeruTokens.Space8,
        ),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        items(results, key = { it.id }) { anime ->
            ResultCard(anime, addAction(libraryIds, adding, anime.id), onPlanned, onOpen)
        }
    }
}

/**
 * One answer: the artwork opens the title, and the control under it does the one thing a viewer
 * usually wants from a search result without reading anything first.
 *
 * The control keeps its place whatever it says, so a grid does not reflow when a write starts or
 * finishes; it simply stops being pressable and states what happened.
 */
@Composable
private fun ResultCard(anime: Anime, action: AddAction, onPlanned: (Int) -> Unit, onOpen: (Int) -> Unit) {
    Column {
        PosterCard(
            posterUrl = anime.posterUrl,
            title = anime.title,
            onClick = { onOpen(anime.id) },
            modifier = Modifier.fillMaxWidth(),
            // The year rides on the artwork rather than under the name: it is the one thing that
            // tells two seasons of the same show apart, and as a badge it costs the card no height.
            badge = anime.year?.toString(),
            // The cell decides how wide a card is here, not the design system's row pitch.
            width = Dp.Unspecified,
            // Two lines reserved, so every «В планы» in a row sits on the same line.
            titleMinLines = 2,
        )
        SecondaryButton(
            text = action.label,
            onClick = { onPlanned(anime.id) },
            modifier = Modifier.fillMaxWidth().padding(top = KaeruTokens.Space2),
            enabled = action.enabled,
            compact = true,
        )
    }
}
