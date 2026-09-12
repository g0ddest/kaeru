package app.kaeru.ui.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
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
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.details.DetailsViewModel
import app.kaeru.ui.mobile.home.HomeScreen
import app.kaeru.ui.mobile.library.LibraryScreen
import app.kaeru.ui.mobile.library.LibraryViewModel
import app.kaeru.ui.mobile.search.SearchScreen
import app.kaeru.ui.mobile.search.SearchViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(Tab(Routes.HOME, "Главная", Icons.Default.Home), Tab(Routes.LIBRARY, "Мой список", Icons.Default.VideoLibrary), Tab(Routes.SEARCH, "Поиск", Icons.Default.Search))

@Composable
fun MobileShell(nav: NavHostController = rememberNavController()) {
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(bottomBar = {
        if (route in tabs.map { it.route }) NavigationBar {
            tabs.forEach { tab -> NavigationBarItem(
                selected = route == tab.route,
                onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            ) }
        }
    }) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { val vm: HomeViewModel = hiltViewModel(); HomeScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::refresh) { nav.navigate(Routes.details(it)) } }
            composable(Routes.LIBRARY) { val vm: LibraryViewModel = hiltViewModel(); LibraryScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::selectStatus, vm::selectSort) { nav.navigate(Routes.details(it)) } }
            composable(Routes.SEARCH) { val vm: SearchViewModel = hiltViewModel(); SearchScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::setQuery, vm::submit, vm::useRecent, vm::addToPlanned) }
            composable(Routes.DETAILS, arguments = listOf(navArgument("animeId") { type = NavType.IntType })) { entry ->
                val vm: DetailsViewModel = hiltViewModel(entry)
                DetailsScreen(vm.uiState.collectAsStateWithLifecycle().value, { nav.popBackStack() }, vm::setStatus)
            }
        }
    }
}
