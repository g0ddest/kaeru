package app.kaeru.ui.mobile

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.details.DetailsViewModel
import app.kaeru.ui.common.downloads.DownloadsViewModel
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.library.LibraryViewModel
import app.kaeru.ui.common.search.SearchViewModel
import app.kaeru.ui.common.pairing.PairingStage
import app.kaeru.ui.common.pairing.PairingUiState
import app.kaeru.ui.common.settings.SettingsViewModel
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.downloads.DownloadsScreen
import app.kaeru.ui.mobile.home.HomeScreen
import app.kaeru.ui.mobile.library.LibraryScreen
import app.kaeru.ui.mobile.pairing.PairingScreen
import app.kaeru.ui.mobile.player.PlayerActivity
import app.kaeru.ui.mobile.search.SearchScreen
import app.kaeru.ui.mobile.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Default.Home),
    Tab(Routes.LIBRARY, "Мой список", Icons.Default.VideoLibrary),
    Tab(Routes.SEARCH, "Поиск", Icons.Default.Search),
)

private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
fun MobileShell(
    pairing: PairingUiState = PairingUiState(),
    onConfirmPairing: () -> String? = { null },
    onDismissPairing: () -> Unit = {},
    route: String? = null,
    onRouteConsumed: () -> Unit = {},
    nav: NavHostController = rememberNavController(),
) {
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    // A television's QR code arrives as a deep link rather than as a tap, so the screen it opens
    // is pushed from here rather than reached from a tab. Single top, because a second scan while
    // the question is already on screen is the same question.
    LaunchedEffect(pairing.stage) {
        if (pairing.stage != PairingStage.IDLE && current != Routes.PAIR) {
            nav.navigate(Routes.PAIR) { launchSingleTop = true }
        }
    }
    // A notification asking for a screen: pushed once and then forgotten, so returning to the app
    // later lands wherever the viewer left it rather than back on «Загрузки».
    LaunchedEffect(route) {
        if (route == null) return@LaunchedEffect
        if (current != route) nav.navigate(route) { launchSingleTop = true }
        onRouteConsumed()
    }
    val context = LocalContext.current
    // Playback is its own activity: landscape, immersive, and outliving this back stack.
    val play: (Int, Int) -> Unit = { animeId, episode ->
        context.startActivity(PlayerActivity.intent(context, animeId, episode))
    }
    val openTab: (String) -> Unit = { tab ->
        nav.navigate(tab) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openAnime: (Int) -> Unit = { animeId -> nav.navigate(Routes.details(animeId)) }
    Scaffold(bottomBar = { if (current in tabRoutes) BottomBar(current, openTab) }) { padding ->
        // Only the bottom inset is handed down. The home screen runs its artwork under the status
        // bar and carries that inset in its own top bar; the screens that are not edge-to-edge yet
        // take it here, one wrapper each, until their own task rebuilds them.
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            // A fade-through at the app's own speed, in place of navigation's 700ms default. The
            // spec asks for a shared-element poster into the title screen; that needs the whole
            // host wrapped in a SharedTransitionLayout and the scope threaded through every screen
            // that draws a poster, which is a change to four screens rather than to this one, so it
            // waits for the television pass that touches them all.
            enterTransition = { fadeIn(tween(KaeruTokens.DurationNormal)) },
            exitTransition = { fadeOut(tween(KaeruTokens.DurationNormal)) },
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = hiltViewModel()
                // The catalogue rows are loaded by the screen that draws them, and this one does.
                LaunchedEffect(vm) { vm.loadDiscover() }
                val state = vm.uiState.collectAsStateWithLifecycle().value
                // Once the hero is on screen, resolve what it offers: the viewer spends a few
                // seconds reading it, and that is exactly what the press after it used to cost.
                LaunchedEffect(state.feed.top) { vm.prefetchTopCard() }
                HomeScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onPlay = play,
                    onAnime = openAnime,
                    onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onSearch = { openTab(Routes.SEARCH) },
                    onSeason = vm::selectSeason,
                    onRetrySeason = vm::retrySeason,
                )
            }
            composable(Routes.LIBRARY) {
                val vm: LibraryViewModel = hiltViewModel()
                // No status-bar wrapper: the screen draws its own top bar, which carries the inset.
                LibraryScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onStatus = vm::selectStatus,
                    onSort = vm::selectSort,
                    onAnime = openAnime,
                    onSearch = { openTab(Routes.SEARCH) },
                )
            }
            composable(Routes.SEARCH) {
                val vm: SearchViewModel = hiltViewModel()
                SearchScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onQuery = vm::setQuery,
                    onSubmit = vm::submit,
                    onRetry = vm::retry,
                    onRecent = vm::useRecent,
                    onPlanned = vm::addToPlanned,
                    onOpen = openAnime,
                )
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onBack = { nav.popBackStack() },
                    onSignOut = vm::signOut,
                    onAutoplay = vm::setAutoplayNext,
                    onQuality = vm::setDefaultQuality,
                    onThreshold = vm::setWatchedThreshold,
                    onStudioUp = vm::moveStudioUp,
                    onStudioDown = vm::moveStudioDown,
                    onStudioRemove = vm::removeStudio,
                    onStudioAdd = vm::addStudio,
                    onStudiosReset = vm::resetStudios,
                    onKodikToken = vm::setKodikToken,
                    onRetryAccount = vm::refreshAccount,
                    onDownloads = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                )
            }
            composable(Routes.DOWNLOADS) {
                val vm: DownloadsViewModel = hiltViewModel()
                DownloadsScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onBack = { nav.popBackStack() },
                    onRemove = vm::remove,
                    onRemoveTitle = vm::removeTitle,
                    onRemoveAll = vm::removeAll,
                    onQuality = vm::setQuality,
                    onWifiOnly = vm::setWifiOnly,
                    onDeleteWatched = vm::setDeleteWatched,
                    onLimit = vm::setLimit,
                )
            }
            composable(Routes.PAIR) {
                PairingScreen(
                    state = pairing,
                    onConfirm = onConfirmPairing,
                    onDismiss = {
                        onDismissPairing()
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.DETAILS, arguments = listOf(navArgument("animeId") { type = NavType.IntType })) { entry ->
                val vm: DetailsViewModel = hiltViewModel(entry)
                // No status-bar wrapper: the title screen runs its artwork under the status bar and
                // carries that inset in its own floating top bar, as the home screen does.
                DetailsScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onBack = { nav.popBackStack() },
                    onRetry = vm::retry,
                    onStatus = vm::setStatus,
                    onPlay = play,
                    onLoadTranslations = vm::loadTranslations,
                    onPickTranslation = vm::pickTranslation,
                    onMarkWatched = vm::markWatched,
                    onDownload = vm::download,
                    onRemoveDownload = vm::removeDownload,
                    onStorageMessageShown = vm::storageMessageShown,
                    onDownloads = { nav.navigate(Routes.DOWNLOADS) },
                )
            }
        }
    }
}

/**
 * Where you are, and the two other places you can be.
 *
 * The bar sits on the app's own surface rather than Material's tinted one, and the active item is
 * the only amber thing down here: the indicator behind it is a step of the same near-black, so the
 * colour marks the destination rather than the pill around it.
 *
 * Icons carry no description on purpose. The label is right beside each one and the item merges
 * both into a single announcement, so a description would make a screen reader say it twice.
 */
@Composable
private fun BottomBar(route: String?, onTab: (String) -> Unit) {
    NavigationBar(containerColor = KaeruSurface, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = { onTab(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = KaeruAccent,
                    selectedTextColor = KaeruAccent,
                    indicatorColor = KaeruElevated,
                    unselectedIconColor = KaeruSecondary,
                    unselectedTextColor = KaeruSecondary,
                ),
            )
        }
    }
}
