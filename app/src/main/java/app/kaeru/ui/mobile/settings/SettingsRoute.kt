package app.kaeru.ui.mobile.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        // Only a yes turns the setting on. A refusal leaves it exactly where it was, which is off.
        if (granted) vm.setNewEpisodes(true)
    }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onSignOut = vm::signOut,
        onAutoplay = vm::setAutoplayNext,
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
        notificationsRefused = refused,
    )
}

/**
 * The system's notification question, put once and only after a sign-in that happened here.
 *
 * Draws nothing. It is mounted for exactly as long as there is a question to put, and a refusal
 * takes «Новые серии» off so the settings page has something true to show and a labelled way back.
 */
@Composable
fun NewEpisodesPermissionPrompt(
    onDone: () -> Unit,
    vm: NotificationPromptViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.answered(granted)
        onDone()
    }
    LaunchedEffect(Unit) {
        val offer = shouldOfferNotifications(
            sdkInt = Build.VERSION.SDK_INT,
            granted = notificationsGranted(context),
            alreadyAsked = vm.alreadyAsked(),
            wanted = vm.wanted(),
        )
        if (!offer) {
            onDone()
            return@LaunchedEffect
        }
        // A device with nothing to answer the request throws rather than refusing.
        if (runCatching { ask.launch(POST_NOTIFICATIONS) }.isFailure) onDone()
    }
}
