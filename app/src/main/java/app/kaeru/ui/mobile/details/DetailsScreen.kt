package app.kaeru.ui.mobile.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.Skeleton

@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStatus: (ListStatus) -> Unit,
) {
    val entry = state.entry
    val anime = state.anime
    if (anime == null) {
        // Nothing cached for this anime: show progress only while a load is actually running,
        // then the failure and a retry. The back button is always there so this is never a dead end.
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Button(onClick = onBack) { Text("Назад") }
            if (state.refreshing) {
                Skeleton(Modifier.fillMaxWidth().height(300.dp))
            } else {
                Text(
                    state.errorMessage ?: "Не удалось загрузить аниме",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) { Text("Повторить") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Button(onClick = onBack) { Text("Назад") }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Poster(anime.posterUrl, anime.title, Modifier.width(132.dp).height(198.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    anime.year?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("${anime.availableEpisodes} серий", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    anime.score?.let { Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                anime.studio?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        Text("Список", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ListStatus.WATCHING to "Смотрю", ListStatus.PLANNED to "В планах", ListStatus.COMPLETED to "Завершено").forEach { (status, label) ->
                FilterChip(selected = entry?.rate?.status == status, onClick = { onStatus(status) }, enabled = !state.updatingStatus, label = { Text(label) })
            }
        }
        if (entry != null) {
            Text("Просмотрено ${entry.rate.episodes} из ${anime.availableEpisodes}", modifier = Modifier.padding(top = 16.dp))
        } else {
            Text("Не в списке. Выберите статус, чтобы добавить", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
        anime.description?.takeIf { it.isNotBlank() }?.let {
            Text("Описание", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Text(it)
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
