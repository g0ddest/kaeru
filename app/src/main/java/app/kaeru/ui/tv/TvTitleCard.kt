package app.kaeru.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import java.time.Instant

private const val EPISODES_PER_ROW = 6
private val TileShape = RoundedCornerShape(10.dp)

/**
 * The title, in two columns: what the show is and what to do about it on the left, the season
 * itself on the right.
 *
 * Focus opens on the watch button, so the one-press rule still holds from here; the grid is one
 * press of right away, for the viewer who wants an episode other than the next one.
 */
@Composable
fun TvTitleCard(item: FeedItem, onWatch: (Int) -> Unit, onClose: () -> Unit) {
    val entry = item.entry
    val anime = entry.anime
    val primary = remember { FocusRequester() }
    val action = remember(item) { tvWatchAction(item) }
    val grid = remember(entry) { tvEpisodeGrid(entry) }
    LaunchedEffect(anime.id) { primary.requestFocusOrLog("главную кнопку карточки тайтла") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxSize(0.88f)) {
            Row(Modifier.padding(36.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Poster(anime.posterUrl, anime.title, Modifier.width(210.dp).height(315.dp))
                TitleColumn(
                    entry = entry,
                    action = action,
                    primary = primary,
                    onWatch = onWatch,
                    onClose = onClose,
                    modifier = Modifier.weight(1f),
                )
                EpisodeGrid(
                    cells = grid,
                    onPlay = onWatch,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TitleColumn(
    entry: LibraryEntry,
    action: TvWatchAction,
    primary: FocusRequester,
    onWatch: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val anime = entry.anime
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(anime.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(statusLine(anime), style = MaterialTheme.typography.titleSmall, color = KaeruSecondary)
        Text("Просмотрено ${entry.rate.episodes} из ${anime.availableEpisodes}")
        if (action is TvWatchAction.NotAired) {
            Text("Следующая серия ещё не вышла", color = KaeruSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action is TvWatchAction.Play) {
                Button(onClick = { onWatch(action.episode) }, modifier = Modifier.focusRequester(primary)) {
                    Text(action.label)
                }
            }
            // Whichever button is first is the one focus opens on, so the requester follows it.
            Button(
                onClick = onClose,
                modifier = if (action is TvWatchAction.Play) Modifier else Modifier.focusRequester(primary),
            ) { Text("Закрыть") }
        }
        anime.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, maxLines = 7, overflow = TextOverflow.Ellipsis, color = KaeruSecondary)
        }
    }
}

@Composable
private fun EpisodeGrid(cells: List<TvEpisodeCell>, onPlay: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Серии", style = MaterialTheme.typography.titleMedium)
            Text("Список серий пока неизвестен", color = KaeruSecondary)
        }
        return
    }
    // Open on what the viewer is going to want: the first episode still ahead of them.
    val next = cells.indexOfFirst { !it.watched && it.aired }.coerceAtLeast(0)
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { gridState.scrollToItem((next - EPISODES_PER_ROW).coerceAtLeast(0)) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Серии", style = MaterialTheme.typography.titleMedium)
        LazyVerticalGrid(
            columns = GridCells.Fixed(EPISODES_PER_ROW),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cells, key = { it.episode }) { cell ->
                TvEpisodeTile(cell, onPlay = { onPlay(cell.episode) })
            }
        }
    }
}

/**
 * One episode. An episode that has not aired is disabled, which on a remote also means the
 * D-pad steps over it rather than landing somewhere nothing happens.
 */
@Composable
private fun TvEpisodeTile(cell: TvEpisodeCell, onPlay: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvEpisodeScale")
    Button(
        onClick = onPlay,
        enabled = cell.aired,
        modifier = Modifier
            .height(64.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, KaeruAccent, TileShape) else Modifier)
            // One spoken sentence instead of a number and a fragment read separately.
            .then(
                if (cell.aired) Modifier
                else Modifier.clearAndSetSemantics { contentDescription = "${cell.episode} серия, ещё не вышла" },
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = TileShape),
        colors = ButtonDefaults.colors(
            containerColor = KaeruElevated,
            contentColor = KaeruText,
            focusedContainerColor = KaeruAccent,
            focusedContentColor = Color.Black,
            disabledContainerColor = KaeruElevated.copy(alpha = 0.45f),
            disabledContentColor = KaeruSecondary.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.episode.toString(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (!cell.aired) {
                    Text("не вышла", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            if (cell.watched) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Просмотрено",
                    tint = if (focused) Color.Black else KaeruAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp),
                )
            }
            cell.progress?.let { fraction ->
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(KaeruAccent))
                }
            }
        }
    }
}

/** Year, state and studio on one line, only as much of it as the catalogue actually knows. */
private fun statusLine(anime: Anime): String = listOfNotNull(
    anime.year?.toString(),
    when (anime.status) {
        AnimeStatus.ONGOING -> "выходит, ${anime.episodesAired} из ${anime.episodes.takeIf { it > 0 } ?: "?"}"
        AnimeStatus.RELEASED -> "вышло ${anime.availableEpisodes} серий"
        AnimeStatus.ANONS -> "анонс"
    },
    anime.studio?.takeIf { it.isNotBlank() },
).joinToString("    ")

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleCardPreview() {
    val anime = Anime(
        id = 1,
        nameRu = "Восхождение в тени",
        nameRomaji = "Kage no Jitsuryokusha ni Naritakute",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = AnimeStatus.ONGOING,
        episodes = 12,
        episodesAired = 8,
        nextEpisodeAt = null,
        score = 8.2,
        year = 2026,
        studio = "Nexus",
        description = "Мальчик, который хотел быть не героем и не злодеем, а тем, кто стоит в тени.",
    )
    val entry = LibraryEntry(
        anime,
        UserRate(1, 1, ListStatus.WATCHING, 4, Instant.EPOCH),
        watch = null,
    )
    KaeruTvTheme {
        TvTitleCard(FeedItem(entry, episode = 5, kind = FeedKind.CONTINUE), onWatch = {}, onClose = {})
    }
}
