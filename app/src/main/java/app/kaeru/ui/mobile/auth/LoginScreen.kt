package app.kaeru.ui.mobile.auth

import android.net.Uri
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
import app.kaeru.ui.common.auth.AuthUiState

@Composable
fun LoginScreen(authorizeUrl: String, state: AuthUiState) {
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
            onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorizeUrl)) },
            enabled = !state.exchanging,
        ) { Text(if (state.exchanging) "Проверяем код…" else "Войти через Shikimori") }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
