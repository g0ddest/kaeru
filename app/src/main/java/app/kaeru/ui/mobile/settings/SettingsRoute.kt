package app.kaeru.ui.mobile.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaeru.ui.common.settings.NotificationPromptViewModel
import app.kaeru.ui.common.settings.SettingsViewModel

/**
 * The settings page with its view model and the one thing on it that is not a preference: the
 * system's notification permission.
 *
 * A route of its own rather than more lines in the shell, because the «Новые серии» switch is
 * three states rather than one — the setting, the permission, and a refusal that has to be
 * explained — and none of that belongs in a navigation graph.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onUpdates: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Not saved across process death on purpose: it describes an answer the viewer just gave, and
    // a line about a refusal that nobody in this session made would be the screen inventing one.
    // The standing case — the setting on with the permission missing — is the view model's
    // `newEpisodesBlocked`, which needs no refusal to have been heard here.
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        vm.notificationsAllowed(granted)
        // Only a yes turns the setting on. A refusal leaves it exactly where it was, which is off.
        if (granted) vm.setNewEpisodes(true)
    }
    // Read on every return to the screen, not once when it is built. The permission is changed
    // somewhere this app cannot see — the system's own settings, a page the viewer reaches from
    // the very line below the switch — and coming back to find the switch still claiming «on» is
    // the failure this screen exists to prevent.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.notificationsAllowed(notificationsGranted(context))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onSignOut = vm::signOut,
        onAutoplay = vm::setAutoplayNext,
        onSkipEnding = vm::setSkipEnding,
        onPipOnLeave = vm::setPipOnLeave,
        onNewEpisodes = { wanted ->
            when (pressOfNewEpisodes(wanted, Build.VERSION.SDK_INT, notificationsGranted(context))) {
                NewEpisodesPress.TURN_ON -> {
                    refused = false
                    vm.setNewEpisodes(true)
                }
                NewEpisodesPress.TURN_OFF -> {
                    refused = false
                    vm.setNewEpisodes(false)
                }
                // A device with nothing to answer the request throws rather than refusing.
                NewEpisodesPress.ASK_FIRST -> runCatching { ask.launch(POST_NOTIFICATIONS) }
            }
        },
        onQuality = vm::setDefaultQuality,
        onThreshold = vm::setWatchedThreshold,
        onStudioUp = vm::moveStudioUp,
        onStudioDown = vm::moveStudioDown,
        onStudioRemove = vm::removeStudio,
        onStudioAdd = vm::addStudio,
        onStudiosReset = vm::resetStudios,
        onKodikToken = vm::setKodikToken,
        onRetryAccount = vm::refreshAccount,
        onDownloads = onDownloads,
        onUpdates = onUpdates,
        notificationsBlocked = state.newEpisodesBlocked || refused,
    )
}

/**
 * The system's notification question, put once and only after a sign-in that happened here.
 *
 * Draws nothing, and is mounted for as long as somebody is signed in: what decides whether the
 * question goes up is a fact in the store, not whether this composition exists. That is the whole
 * point of it living there — a phone turned on its side between the login screen and the shell used
 * to lose the question, and the viewer was left with the setting on and no permission behind it.
 *
 * A refusal takes «Новые серии» off, so the settings page has something true to show and a
 * labelled way back.
 */
@Composable
fun NewEpisodesPermissionPrompt(vm: NotificationPromptViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val owed by vm.owed.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::answered)
    LaunchedEffect(owed) {
        if (!owed) return@LaunchedEffect
        val offer = shouldOfferNotifications(
            sdkInt = Build.VERSION.SDK_INT,
            granted = notificationsGranted(context),
            alreadyAsked = vm.alreadyAsked(),
            wanted = vm.wanted(),
        )
        if (!offer) {
            vm.dismiss()
            return@LaunchedEffect
        }
        // A device with nothing to answer the request throws rather than refusing.
        if (runCatching { ask.launch(POST_NOTIFICATIONS) }.isFailure) vm.dismiss()
    }
}
