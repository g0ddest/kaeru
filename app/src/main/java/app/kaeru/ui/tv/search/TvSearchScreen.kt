package app.kaeru.ui.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Anime
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.SearchField
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.SkeletonGrid
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TvPosterCard
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.search.SearchContent
import app.kaeru.ui.common.search.SearchUiState
import app.kaeru.ui.common.search.addAction
import app.kaeru.ui.common.search.searchContentState
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvPreviewAnime

private const val IDLE_TITLE = "Что посмотреть сегодня?"
private const val IDLE_TEXT =
    "Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."
private const val NOT_FOUND_TITLE = "Ничего не найдено"
private const val NOT_FOUND_TEXT = "Попробуйте оригинальное название или короче."
private const val PLACEHOLDER = "Название аниме"

/** Five across a 1080p panel: wide enough to read a poster, narrow enough to show two rows. */
private const val GRID_COLUMNS = 5
private const val SKELETON_CELLS = 10

/** The field is a control, not a banner: it takes the width a name needs and no more. */
private val FieldWidth = 560.dp

/**
 * Where a title that is not in the list yet gets found, on a television.
 *
 * One field and the answers under it. The keyboard is the platform's — a remote has no keys — so
 * the field carries no «Найти» of its own: the on-screen keyboard's search key submits, exactly as
 * it does on the phone.
 *
 * The queries already typed sit under the field as chips, and on a television they matter more
 * than they do in the hand: typing a name with a D-pad is slow enough that running the same search
 * twice is worth one press instead of twenty.
 */
@Composable
fun TvSearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onRecent: (String) -> Unit,
    onPlanned: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocusOrLog("the television search field") }
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = TvLayout.Gutter,
                end = TvLayout.GutterEnd,
                top = TvLayout.SafeVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = onQuery,
            onSubmit = onSubmit,
            placeholder = PLACEHOLDER,
            modifier = Modifier.width(FieldWidth).focusRequester(field),
        )
        if (state.recentQueries.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                state.recentQueries.forEach { query ->
                    StatusPill(
                        text = query,
                        selected = false,
                        onClick = { onRecent(query) },
                        role = Role.Button,
                        affordance = false,
                    )
                }
            }
        }
        when (val content = searchContentState(state)) {
            SearchContent.Idle -> EmptyState(IDLE_TITLE, IDLE_TEXT, Modifier.fillMaxSize())
            SearchContent.Loading -> SkeletonGrid(
                count = SKELETON_CELLS,
                gutter = 0.dp,
                columns = GRID_COLUMNS,
            )
            SearchContent.NotFound -> EmptyState(NOT_FOUND_TITLE, NOT_FOUND_TEXT, Modifier.fillMaxSize())
            is SearchContent.Error -> ErrorState(content.message, onRetry, Modifier.fillMaxSize())
            SearchContent.Results -> TvResultGrid(state, onPlanned, onOpen)
        }
    }
}

@Composable
private fun TvResultGrid(state: SearchUiState, onPlanned: (Int) -> Unit, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(
            top = KaeruTokens.Space2,
            bottom = TvLayout.SafeVertical,
        ),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        items(state.results, key = { it.id }) { anime ->
            TvResultCard(
                anime = anime,
                inList = anime.id in state.libraryIds,
                adding = state.addingAnimeId == anime.id,
                onPlanned = { onPlanned(anime.id) },
                onOpen = { onOpen(anime.id) },
            )
        }
    }
}

/**
 * A result, and the one thing worth doing with it without opening it.
 *
 * The control under the card is the same decision the phone makes — «В планы», «Добавляем…», «В
 * списке» — read out of the same `addAction`, so a title added on one screen reads the same way on
 * the other.
 */
@Composable
private fun TvResultCard(
    anime: Anime,
    inList: Boolean,
    adding: Boolean,
    onPlanned: () -> Unit,
    onOpen: () -> Unit,
) {
    val action = addAction(
        libraryIds = if (inList) setOf(anime.id) else emptySet(),
        adding = anime.id.takeIf { adding },
        animeId = anime.id,
    )
    Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
        TvPosterCard(
            posterUrl = anime.posterUrl,
            title = anime.title,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            subtitle = anime.catalogueLine(),
            width = Dp.Unspecified,
        )
        SecondaryButton(
            text = action.label,
            onClick = onPlanned,
            enabled = action.enabled,
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How much show there is, or who is making it when none of it has aired yet. */
private fun Anime.catalogueLine(): String? =
    availableEpisodes.takeIf { it > 0 }?.let(::pluralEpisodes) ?: studio

private val previewResults = listOf(
    tvPreviewAnime(1, "Фрирен, провожающая в последний путь"),
    tvPreviewAnime(2, "Дандадан"),
    tvPreviewAnime(3, "Магическая битва"),
    tvPreviewAnime(4, "Восхождение в тени"),
    tvPreviewAnime(5, "Ванпанчмен"),
    tvPreviewAnime(6, "Клинок, рассекающий демонов"),
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSearchResultsPreview() = KaeruTvTheme {
    TvSearchScreen(
        state = SearchUiState(
            query = "фрирен",
            results = previewResults,
            recentQueries = listOf("фрирен", "дандадан"),
            hasSearched = true,
            libraryIds = setOf(2),
        ),
        onQuery = {},
        onSubmit = {},
        onRetry = {},
        onRecent = {},
        onPlanned = {},
        onOpen = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSearchIdlePreview() = KaeruTvTheme {
    Box(Modifier.fillMaxSize()) {
        TvSearchScreen(
            state = SearchUiState(),
            onQuery = {},
            onSubmit = {},
            onRetry = {},
            onRecent = {},
            onPlanned = {},
            onOpen = {},
        )
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSearchNotFoundPreview() = KaeruTvTheme {
    TvSearchScreen(
        state = SearchUiState(query = "ффф", hasSearched = true),
        onQuery = {},
        onSubmit = {},
        onRetry = {},
        onRecent = {},
        onPlanned = {},
        onOpen = {},
    )
}
