package app.kaeru.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.details.DetailsViewModel
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.library.LibraryViewModel
import app.kaeru.ui.common.search.SearchViewModel
import app.kaeru.ui.common.settings.SettingsViewModel
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.details.TvTitleScreen
import app.kaeru.ui.tv.home.TvHomeScreen
import app.kaeru.ui.tv.library.TvLibraryScreen
import app.kaeru.ui.tv.search.TvSearchScreen
import app.kaeru.ui.tv.settings.TvSettingsScreen

private const val HOME = "Главная"
private const val LIBRARY = "Мой список"
private const val SEARCH = "Поиск"
private const val SETTINGS = "Настройки"

/** The inset that carries the rail's icons clear of the five per cent a panel may crop. */
private val RailInset = KaeruTokens.Space6

private data class TvTab(val destination: TvDestination, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TvTab(TvDestination.HOME, HOME, Icons.Default.Home),
    TvTab(TvDestination.LIBRARY, LIBRARY, Icons.Default.VideoLibrary),
    TvTab(TvDestination.SEARCH, SEARCH, Icons.Default.Search),
    TvTab(TvDestination.SETTINGS, SETTINGS, Icons.Default.Settings),
)

/**
 * The television's four destinations behind one drawer.
 *
 * The drawer is modal rather than standard, which is what lets the home screen's artwork run to the
 * left edge of the panel under it: a standard drawer sits beside the content and would squeeze
 * every row sideways each time the rail took focus. Closed it is [TvLayout.RailWidth] of icons, and
 * every screen inside starts its content at exactly that width — so nothing focusable is ever drawn
 * under the rail, and nothing jumps when the rail opens.
 *
 * Each destination keeps its own scroll position and its own focused card, held here rather than in
 * the screen: a title card takes the screen down while it is open, and «where was I» has to outlive
 * that. Back closes the title card first, then walks to the home screen, then leaves the app.
 */
@Composable
fun TvShell(onPlay: (animeId: Int, episode: Int) -> Unit) {
    var route by rememberSaveable(stateSaver = TvRouteSaver) { mutableStateOf(TvRoute()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val home = rememberTvDestinationState()
    val library = rememberTvDestinationState()
    val content = remember { FocusRequester() }

    // Null means there is nowhere left to go and the press belongs to the launcher, so the
    // handler steps aside rather than swallowing it.
    val back = route.back()
    BackHandler(enabled = back != null) { back?.let { route = it } }

    // Registered after the route's, because the dispatcher asks the most recently added enabled
    // handler first: with the rail open, back belongs to the rail and to nothing else.
    //
    // Closing it means moving focus out of it rather than setting the drawer's value.
    // `ModalNavigationDrawer` registers no back handler of its own and re-derives open from
    // whether anything inside it has focus, so a value set behind a rail that still holds the
    // D-pad would render closed and behave open.
    BackHandler(enabled = drawer.currentValue == DrawerValue.Open) {
        content.requestFocusOrLog("the content behind an open rail")
    }

    ModalNavigationDrawer(
        drawerContent = { value -> TvRail(value, route.destination) { route = route.open(it) } },
        drawerState = drawer,
        scrimBrush = Brush.horizontalGradient(
            0f to KaeruBackground,
            0.45f to KaeruBackground.copy(alpha = 0.72f),
            1f to Color.Transparent,
        ),
    ) {
        // A focus group, so that one request can hand the D-pad back to the screen without naming
        // anything on it: the group passes the focus on to a child, and which child that is stays
        // the screen's business.
        Box(Modifier.fillMaxSize().focusRequester(content).focusGroup()) {
            val titleId = route.titleId
            // The title card replaces its destination rather than covering it. A screen still
            // composed under an overlay is a screen the D-pad can walk back into, which on a
            // television means focus disappearing into rows nobody can see; the scroll position and
            // the focused card the destination would lose are held above it here instead.
            if (titleId != null) {
                TvTitle(titleId, onPlay)
            } else {
                when (route.destination) {
                    TvDestination.HOME -> TvHome(route, home, onPlay) { route = it }
                    TvDestination.LIBRARY -> TvLibrary(route, library) { route = it }
                    TvDestination.SEARCH -> TvSearch(route) { route = it }
                    TvDestination.SETTINGS -> TvSettings()
                }
            }
        }
    }
}

/**
 * Where one destination was: which card the remote sat on, and how far down — and along — its lists
 * it was.
 *
 * Held here rather than in the screen because the screen is taken down every time a title card
 * opens. Each destination uses the containers it actually has: the home screen the vertical [list]
 * and the per-row [rows], the library the [grid]. Two unused handles is the price of one shape of
 * memory for every destination, and they cost an object each.
 */
class TvDestinationState(
    val list: LazyListState,
    val grid: LazyGridState,
    val rows: TvRowStates,
    val focus: TvFocusMemory,
)

@Composable
private fun rememberTvDestinationState(): TvDestinationState {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val rows = remember { TvRowStates() }
    val focus = rememberTvFocusMemory()
    return remember(list, grid, rows, focus) { TvDestinationState(list, grid, rows, focus) }
}

/**
 * The rail: four icons, and their names once it has focus.
 *
 * The open state is reached by focus alone — tv-material opens the drawer when anything inside it
 * takes focus — so there is no button to press to see the labels and no state to remember. The one
 * that is selected is amber, which is the same thing the accent says on the phone's bottom bar:
 * this is the one you are looking at.
 */
@Composable
private fun NavigationDrawerScope.TvRail(
    value: DrawerValue,
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(if (value == DrawerValue.Open) KaeruBackground else Color.Transparent)
            .padding(start = RailInset, top = TvLayout.SafeVertical, bottom = TvLayout.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        tabs.forEach { tab ->
            NavigationDrawerItem(
                selected = tab.destination == selected,
                onClick = { onSelect(tab.destination) },
                leadingContent = {
                    Icon(
                        tab.icon,
                        // The label beside it says the same thing once the rail is open, and a
                        // screen reader reads the item as one announcement either way.
                        contentDescription = tab.label,
                        tint = if (tab.destination == selected) KaeruAccent else KaeruSecondary,
                        modifier = Modifier.size(NavigationDrawerItemDefaults.IconSize),
                    )
                },
            ) {
                Text(
                    tab.label,
                    color = if (tab.destination == selected) KaeruAccent else KaeruText,
                )
            }
        }
    }
}

@Composable
private fun TvHome(
    route: TvRoute,
    state: TvDestinationState,
    onPlay: (Int, Int) -> Unit,
    onRoute: (TvRoute) -> Unit,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val home = viewModel.uiState.collectAsStateWithLifecycle().value
    // The catalogue rows are loaded by the screen that draws them, and the television draws them.
    LaunchedEffect(viewModel) { viewModel.loadDiscover() }
    TvHomeScreen(
        state = home,
        onRefresh = viewModel::refresh,
        onPlay = onPlay,
        onDetails = { onRoute(route.openTitle(it)) },
        onSeason = viewModel::selectSeason,
        onRetrySeason = viewModel::retrySeason,
        onSearch = { onRoute(route.open(TvDestination.SEARCH)) },
        listState = state.list,
        rowStates = state.rows,
        focus = state.focus,
    )
}

@Composable
private fun TvLibrary(route: TvRoute, destination: TvDestinationState, onRoute: (TvRoute) -> Unit) {
    val viewModel: LibraryViewModel = hiltViewModel()
    TvLibraryScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onStatus = viewModel::selectStatus,
        onAnime = { onRoute(route.openTitle(it)) },
        onSearch = { onRoute(route.open(TvDestination.SEARCH)) },
        gridState = destination.grid,
        focus = destination.focus,
    )
}

@Composable
private fun TvSearch(route: TvRoute, onRoute: (TvRoute) -> Unit) {
    val viewModel: SearchViewModel = hiltViewModel()
    TvSearchScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onQuery = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onRetry = viewModel::retry,
        onRecent = viewModel::useRecent,
        onPlanned = viewModel::addToPlanned,
        onOpen = { onRoute(route.openTitle(it)) },
    )
}

@Composable
private fun TvSettings() {
    val viewModel: SettingsViewModel = hiltViewModel()
    TvSettingsScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onSignOut = viewModel::signOut,
        onAutoplay = viewModel::setAutoplayNext,
        onQuality = viewModel::setDefaultQuality,
        onThreshold = viewModel::setWatchedThreshold,
        onStudioUp = viewModel::moveStudioUp,
        onStudioDown = viewModel::moveStudioDown,
        onStudioRemove = viewModel::removeStudio,
        onStudiosReset = viewModel::resetStudios,
        onRetryAccount = viewModel::refreshAccount,
    )
}

/** The title card, on a view model scoped to the title it is about. */
@Composable
private fun TvTitle(animeId: Int, onPlay: (Int, Int) -> Unit) = TvAnimeScope(animeId) {
    val viewModel: DetailsViewModel = hiltViewModel()
    TvTitleScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onRetry = viewModel::retry,
        onStatus = viewModel::setStatus,
        onPlay = onPlay,
        onLoadTranslations = viewModel::loadTranslations,
        onPickTranslation = viewModel::pickTranslation,
    )
}

/**
 * Two small values, so a destination and the title card over it survive a process death.
 *
 * A television is left on a screen for days and is restarted under it more often than a phone is;
 * coming back to the home screen with nothing open would be the app forgetting a decision the
 * viewer made rather than one the system did.
 */
private val TvRouteSaver: Saver<TvRoute, List<Any>> = Saver(
    save = { listOf(it.destination.name, it.titleId ?: 0) },
    restore = { saved ->
        TvRoute(
            destination = TvDestination.valueOf(saved[0] as String),
            titleId = (saved[1] as Int).takeIf { it != 0 },
        )
    },
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvRailPreview() = KaeruTvTheme {
    Box(Modifier.fillMaxSize().background(KaeruBackground)) {
        ModalNavigationDrawer(
            drawerContent = { value -> TvRail(value, TvDestination.HOME) {} },
            content = { Box(Modifier.fillMaxSize()) },
        )
    }
}
