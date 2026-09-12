package app.kaeru.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.mobile.auth.LoginScreen
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.details.DetailsViewModel
import app.kaeru.ui.mobile.home.HomeScreen

@Composable
fun MobileApp(
    pendingAuthCode: String?,
    onAuthCodeConsumed: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(pendingAuthCode) {
        pendingAuthCode?.let { authViewModel.exchangeMobileCode(it, onAuthCodeConsumed) }
    }
    KaeruTheme {
        when (auth.loggedIn) {
            null -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize())
            false -> LoginScreen(authViewModel.mobileAuthorizeUrl, auth)
            true -> {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        val vm: HomeViewModel = hiltViewModel()
                        val state = vm.uiState.collectAsStateWithLifecycle().value
                        HomeScreen(state, vm::refresh) { nav.navigate(Routes.details(it)) }
                    }
                    composable(
                        Routes.DETAILS,
                        arguments = listOf(navArgument("animeId") { type = NavType.IntType }),
                    ) { backStackEntry ->
                        val vm: DetailsViewModel = hiltViewModel(backStackEntry)
                        DetailsScreen(
                            state = vm.uiState.collectAsStateWithLifecycle().value,
                            onBack = { nav.popBackStack() },
                            onStatus = vm::setStatus,
                        )
                    }
                }
            }
        }
    }
}
