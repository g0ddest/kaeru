package app.kaeru.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.mobile.auth.LoginScreen

/** One OAuth redirect back into the app, exactly as it arrived: neither field is trusted yet. */
data class OAuthCallback(val code: String?, val state: String?)

@Composable
fun MobileApp(
    callback: OAuthCallback?,
    onCallbackConsumed: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(callback) {
        if (callback != null) {
            authViewModel.onMobileCallback(callback.code, callback.state, onCallbackConsumed)
        }
    }
    KaeruTheme {
        when (auth.loggedIn) {
            null -> Box(Modifier.fillMaxSize())
            false -> LoginScreen(authViewModel::mobileAuthorizeUrl, auth)
            true -> MobileShell()
        }
    }
}
