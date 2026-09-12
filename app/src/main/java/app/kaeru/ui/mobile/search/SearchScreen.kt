package app.kaeru.ui.mobile.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.Poster

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecent: (String) -> Unit,
    onPlanned: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        Text("Поиск", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Название аниме") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit() }),
            trailingIcon = { Button(onClick = onSubmit, enabled = !state.searching) { Text("Найти") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (state.recentQueries.isNotEmpty()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentQueries.forEach { query -> InputChip(selected = false, onClick = { onRecent(query) }, label = { Text(query) }) }
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.results, key = { it.id }) { anime ->
                val inLibrary = anime.id in state.libraryIds
                Column(Modifier.clickable { onOpen(anime.id) }) {
                    Poster(anime.posterUrl, anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Text(anime.title, maxLines = 2)
                    Button(
                        onClick = { onPlanned(anime.id) },
                        enabled = !inLibrary && state.addingAnimeId != anime.id,
                    ) { Text(if (inLibrary) "В списке ✓" else if (state.addingAnimeId == anime.id) "Добавляем…" else "В планы") }
                }
            }
        }
    }
}
