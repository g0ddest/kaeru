package app.kaeru.ui.mobile

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruText

private const val RETRY = "Повторить"

/**
 * A failure over content the viewer can still use: the message explains itself and the action
 * repeats what failed.
 *
 * Keyed on the message, so the same failure twice does not nag. A screen with nothing to show
 * uses `ErrorState` instead — the two together would put two «Повторить» on one screen.
 */
@Composable
fun RetrySnackbar(message: String?, host: SnackbarHostState, onRetry: () -> Unit) {
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(message, actionLabel = RETRY, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) retry()
    }
}

/**
 * A message whose way forward is somewhere else: a full device, and the screen where the space is.
 *
 * Apart from [RetrySnackbar] because the action is not a repeat. A download the storage limit
 * refused would be refused again by the same limit, so offering «Повторить» would be offering the
 * viewer the same wall twice; what they need is the screen with the delete controls on it.
 *
 * [onShown] fires when the snackbar closes, however it closed, so the state that produced it can be
 * cleared and the next refusal is news rather than a repeat of one already read.
 */
@Composable
fun ActionSnackbar(
    message: String?,
    actionLabel: String,
    host: SnackbarHostState,
    onAction: () -> Unit,
    onShown: () -> Unit,
) {
    val act by rememberUpdatedState(onAction)
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) act()
        shown()
    }
}

/**
 * Where those messages appear.
 *
 * The default snackbar is a pale slab in a dark app. This one is the app's own elevated surface,
 * and its action stays in text colour: the amber belongs to the watch button behind it, which is
 * still what the screen is for.
 */
@Composable
fun KaeruSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = KaeruTokens.CardShape,
            containerColor = KaeruElevated,
            contentColor = KaeruText,
            actionColor = KaeruText,
        )
    }
}
