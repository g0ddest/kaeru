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
import app.kaeru.ui.common.pairing.PairingStage
import app.kaeru.ui.common.pairing.PairingViewModel
import app.kaeru.ui.common.together.TogetherViewModel
import app.kaeru.ui.mobile.auth.LoginScreen
import app.kaeru.ui.mobile.pairing.PairingScreen

/** One OAuth redirect back into the app, exactly as it arrived: neither field is trusted yet. */
data class OAuthCallback(val code: String?, val state: String?)

@Composable
fun MobileApp(
    callback: OAuthCallback?,
    onCallbackConsumed: () -> Unit,
    pairingLink: String? = null,
    onPairingLinkConsumed: () -> Unit = {},
    route: String? = null,
    onRouteConsumed: () -> Unit = {},
    watchLink: String? = null,
    onWatchLinkConsumed: () -> Unit = {},
    authViewModel: AuthViewModel = hiltViewModel(),
    pairingViewModel: PairingViewModel = hiltViewModel(),
    togetherViewModel: TogetherViewModel = hiltViewModel(),
) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    val pairing = pairingViewModel.uiState.collectAsStateWithLifecycle().value
    val together = togetherViewModel.uiState.collectAsStateWithLifecycle().value
    // Opening the room is what makes the other phone say what it is watching, which is the whole
    // of the screen the viewer then decides on. Consumed once: a rotation must not knock twice.
    LaunchedEffect(watchLink) {
        if (watchLink != null) {
            togetherViewModel.open(watchLink)
            onWatchLinkConsumed()
        }
    }
    LaunchedEffect(pairingLink) {
        if (pairingLink != null) {
            pairingViewModel.open(pairingLink)
            onPairingLinkConsumed()
        }
    }
    // One `kaeru://oauth` callback, two things that could want it. The pairing screen is asked
    // first and only takes the callback while it is actually waiting for one, so the phone's own
    // sign-in keeps every callback that is not part of a hand-off in progress.
    LaunchedEffect(callback) {
        if (callback != null &&
            !pairingViewModel.onMobileCallback(callback.code, callback.state, onCallbackConsumed)
        ) {
            authViewModel.onMobileCallback(callback.code, callback.state, onCallbackConsumed)
        }
    }
    KaeruTheme {
        when {
            auth.loggedIn == null -> Box(Modifier.fillMaxSize())
            auth.loggedIn == true -> MobileShell(
                pairing = pairing,
                onConfirmPairing = pairingViewModel::confirm,
                onDismissPairing = pairingViewModel::dismiss,
                // A notification can ask for a screen. It is handed to the shell rather than acted
                // on here, because only the shell has a back stack to push it onto — and it is
                // dropped while signed out, where there is no shell to push anything onto.
                route = route,
                onRouteConsumed = onRouteConsumed,
                together = together,
                onJoinTogether = togetherViewModel::join,
                onJoinedTogether = togetherViewModel::joinScreenDone,
                onDismissTogether = togetherViewModel::dismissJoin,
            )
            // Signing a television in does not need this phone to be signed in: what crosses the
            // network is a code from the browser's own Shikimori session, so the hand-off works
            // from a phone that has only just been installed and takes precedence over its login.
            pairing.stage != PairingStage.IDLE -> PairingScreen(
                state = pairing,
                onConfirm = pairingViewModel::confirm,
                onDismiss = pairingViewModel::dismiss,
            )
            else -> LoginScreen(authViewModel::mobileAuthorizeUrl, auth)
        }
    }
}
