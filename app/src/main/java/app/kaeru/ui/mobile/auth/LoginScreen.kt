package app.kaeru.ui.mobile.auth

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.kaeru.ui.common.auth.AuthUiState

/**
 * [authorizeUrl] arms a fresh OAuth `state`, so it is called per sign-in attempt.
 *
 * [invitationWaiting] says somebody tapped «смотреть вместе» on a signed-out app — which is the
 * ordinary path, since the landing page asks a person to install the build and open the link
 * again. Saying so is the difference between a login screen that appeared out of nowhere and one
 * that is a step on the way to the thing the person actually pressed.
 */
@Composable
fun LoginScreen(authorizeUrl: () -> String, state: AuthUiState, invitationWaiting: Boolean = false) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kaeru", style = MaterialTheme.typography.displaySmall)
        Text(
            "Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(
            // Nowhere to send this on a device with no browser and no Custom Tabs provider. The
            // press does nothing rather than crashing on the one screen that has no way forward.
            onClick = {
                runCatching {
                    CustomTabsIntent.Builder().build().launchUrl(context, authorizeUrl().toUri())
                }
            },
            enabled = !state.exchanging,
        ) { Text(if (state.exchanging) "Проверяем код…" else "Войти через Shikimori") }
        if (invitationWaiting) {
            Text(
                "После входа откроем приглашение",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
