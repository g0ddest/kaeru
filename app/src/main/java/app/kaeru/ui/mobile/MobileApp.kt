package app.kaeru.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.mobile.auth.LoginScreen

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
            true -> MobileShell(onLogout = authViewModel::logout)
        }
    }
}
