package app.kaeru.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.details.DetailsViewModel
import app.kaeru.ui.mobile.home.HomeScreen
import app.kaeru.ui.mobile.library.LibraryScreen
import app.kaeru.ui.mobile.library.LibraryViewModel
import app.kaeru.ui.mobile.player.PlayerActivity
import app.kaeru.ui.mobile.search.SearchScreen
import app.kaeru.ui.mobile.search.SearchViewModel
import app.kaeru.ui.mobile.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Default.Home),
    Tab(Routes.LIBRARY, "Мой список", Icons.Default.VideoLibrary),
    Tab(Routes.SEARCH, "Поиск", Icons.Default.Search),
)

private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
fun MobileShell(onLogout: () -> Unit, nav: NavHostController = rememberNavController()) {
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
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
    Scaffold(bottomBar = { if (route in tabRoutes) BottomBar(route, openTab) }) { padding ->
        // Only the bottom inset is handed down. The home screen runs its artwork under the status
        // bar and carries that inset in its own top bar; the screens that are not edge-to-edge yet
        // take it here, one wrapper each, until their own task rebuilds them.
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = hiltViewModel()
                HomeScreen(
                    state = vm.uiState.collectAsStateWithLifecycle().value,
                    onRefresh = vm::refresh,
                    onPlay = play,
                    onAnime = openAnime,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onSearch = { openTab(Routes.SEARCH) },
                )
            }
            composable(Routes.LIBRARY) {
                val vm: LibraryViewModel = hiltViewModel()
                UnderStatusBar {
                    LibraryScreen(
                        vm.uiState.collectAsStateWithLifecycle().value,
                        vm::selectStatus,
                        vm::selectSort,
                        onLogout,
                        openAnime,
                    )
                }
            }
            composable(Routes.SEARCH) {
                val vm: SearchViewModel = hiltViewModel()
                UnderStatusBar {
                    SearchScreen(
                        vm.uiState.collectAsStateWithLifecycle().value,
                        vm::setQuery,
                        vm::submit,
                        vm::useRecent,
                        vm::addToPlanned,
                        openAnime,
                    )
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() }, onLogout = onLogout)
            }
            composable(Routes.DETAILS, arguments = listOf(navArgument("animeId") { type = NavType.IntType })) { entry ->
                val vm: DetailsViewModel = hiltViewModel(entry)
                UnderStatusBar {
                    DetailsScreen(
                        vm.uiState.collectAsStateWithLifecycle().value,
                        { nav.popBackStack() },
                        vm::refresh,
                        vm::setStatus,
                        play,
                    )
                }
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

/** The status-bar inset for the screens that do not yet draw their own chrome over it. */
@Composable
private fun UnderStatusBar(content: @Composable () -> Unit) =
    Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { content() }
