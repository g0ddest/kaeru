package app.kaeru.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.home.TvHomeScreen

@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LibraryEntry?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    KaeruTvTheme {
        when (auth.loggedIn) {
            null -> Box(Modifier.fillMaxSize())
            false -> TvLoginScreen(
                authorizeUrl = authViewModel.tvAuthorizeUrl,
                state = auth,
                code = code,
                onCode = { code = it },
                onSubmit = { authViewModel.exchangeTvCode(code) },
            )
            true -> {
                val home: HomeViewModel = hiltViewModel()
                TvHomeScreen(home.uiState.collectAsStateWithLifecycle().value, home::refresh) { selected = it }
                selected?.let { TvTitleCard(it) { selected = null } }
            }
        }
    }
}

@Composable
private fun TvTitleCard(entry: LibraryEntry, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxSize(0.72f)) {
            Row(Modifier.padding(36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Poster(entry.anime.posterUrl, entry.anime.title, Modifier.weight(0.34f))
                Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(entry.anime.title, style = MaterialTheme.typography.displaySmall)
                    Text("Просмотрено ${entry.rate.episodes} из ${entry.anime.availableEpisodes}")
                    entry.anime.description?.let { Text(it, maxLines = 8) }
                    Button(onClick = onClose) { Text("Назад") }
                }
            }
        }
    }
}
