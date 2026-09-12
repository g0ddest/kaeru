package app.kaeru.ui.mobile.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.Poster
import app.kaeru.ui.mobile.player.CastButton
import app.kaeru.ui.common.ProgressStrip
import app.kaeru.ui.common.Skeleton
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated

private const val EPISODES_PER_ROW = 5

@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStatus: (ListStatus) -> Unit,
    onPlay: (animeId: Int, episode: Int) -> Unit,
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
    val next = entry?.nextEpisode(state.watchedThreshold) ?: 1
    // Two different numbers: what can be played, and what the season is said to hold. The
    // difference is drawn, so an episode that has not aired reads as waiting rather than broken.
    val aired = airedEpisodes(anime)
    val announced = maxOf(anime.episodes, aired)
    val rows = remember(announced) { (1..announced).chunked(EPISODES_PER_ROW) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item(key = "head") {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Назад") }
                    Spacer(Modifier.weight(1f))
                    CastButton()
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Poster(anime.posterUrl, anime.title, Modifier.width(132.dp).height(198.dp))
                    Column(Modifier.weight(1f)) {
                        Text(anime.title, style = MaterialTheme.typography.headlineMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            anime.year?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("${anime.availableEpisodes} серий", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            anime.score?.let { Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        anime.studio?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Button(
                    onClick = { onPlay(anime.id, next) },
                    modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(watchLabel(entry, next), style = MaterialTheme.typography.titleMedium) }
            }
        }
        item(key = "status") {
            Column {
                Text("Список", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ListStatus.WATCHING to "Смотрю",
                        ListStatus.PLANNED to "В планах",
                        ListStatus.COMPLETED to "Завершено",
                    ).forEach { (status, label) ->
                        FilterChip(
                            selected = entry?.rate?.status == status,
                            onClick = { onStatus(status) },
                            enabled = !state.updatingStatus,
                            label = { Text(label) },
                        )
                    }
                }
                if (entry != null) {
                    Text("Просмотрено ${entry.rate.episodes} из ${anime.availableEpisodes}", modifier = Modifier.padding(top = 16.dp))
                } else {
                    Text(
                        "Не в списке. Начните смотреть или выберите статус",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Text("Серии", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            }
        }
        items(rows, key = { it.first() }) { row ->
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { episode ->
                    EpisodeTile(
                        episode = episode,
                        aired = episode <= aired,
                        watched = episode <= (entry?.rate?.episodes ?: 0),
                        progress = entry?.takeIf { it.watch?.episode == episode }?.progressFraction(state.watchedThreshold),
                        onClick = { onPlay(anime.id, episode) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(EPISODES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        anime.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(key = "description") {
                Column {
                    Text("Описание", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    Text(description)
                }
            }
        }
        state.errorMessage?.let { message ->
            item(key = "error") {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
        }
        item(key = "tail") { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * One episode. The check is Shikimori's count, the strip is where this device stopped, and a
 * dimmed tile is an episode that has not aired — three different facts, drawn differently.
 */
@Composable
private fun EpisodeTile(
    episode: Int,
    aired: Boolean,
    watched: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (aired) KaeruElevated else KaeruElevated.copy(alpha = 0.45f))
            .clickable(enabled = aired, onClick = onClick)
            // One spoken sentence instead of a number and a fragment read separately.
            .then(
                if (aired) Modifier
                else Modifier.clearAndSetSemantics { contentDescription = "$episode серия, ещё не вышла" },
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    episode.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        !aired -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        watched -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!aired) {
                    Text(
                        "не вышла",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (watched) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Просмотрено",
                    tint = KaeruAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(14.dp),
                )
            }
        }
        progress?.let { ProgressStrip(it) }
    }
}

private fun watchLabel(entry: LibraryEntry?, next: Int): String {
    val started = entry != null && (entry.rate.episodes > 0 || entry.watch != null)
    return if (started) "Продолжить $next серию" else "Смотреть $next серию"
}

/**
 * What can actually be played. The first episode stays open even when the catalogue claims
 * nothing has aired: the main button offers it, and the source often has it.
 */
private fun airedEpisodes(anime: Anime): Int = maxOf(anime.availableEpisodes, 1)
