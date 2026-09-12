package app.kaeru.ui.mobile.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.ProgressStrip

@Composable
fun LibraryScreen(state: LibraryUiState, onStatus: (ListStatus) -> Unit, onSort: (LibrarySort) -> Unit, onAnime: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Мой список", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(listOf(
                ListStatus.WATCHING to "Смотрю",
                ListStatus.PLANNED to "В планах",
                ListStatus.COMPLETED to "Готово",
                ListStatus.ON_HOLD to "Отложено",
                ListStatus.DROPPED to "Брошено",
                ListStatus.REWATCHING to "Пересматриваю",
            )) { (status, label) ->
                FilterChip(selected = state.status == status, onClick = { onStatus(status) }, label = { Text(label) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.sort == LibrarySort.UPDATED, onClick = { onSort(LibrarySort.UPDATED) }, label = { Text("Недавние") })
            FilterChip(selected = state.sort == LibrarySort.TITLE, onClick = { onSort(LibrarySort.TITLE) }, label = { Text("По названию") })
        }
        if (state.items.isEmpty()) {
            Text("В этом разделе пока пусто", modifier = Modifier.padding(24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.anime.id }) { entry ->
                    Column(Modifier.clickable { onAnime(entry.anime.id) }) {
                        Poster(entry.anime.posterUrl, entry.anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        entry.progressFraction(0.9f)?.let { ProgressStrip(it) }
                        Text(entry.anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${entry.rate.episodes}/${entry.anime.availableEpisodes}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
