package app.kaeru.ui.tv.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruAccent
import coil3.compose.AsyncImage

@Composable
fun TvHomeScreen(state: HomeUiState, onRefresh: () -> Unit, onAnime: (LibraryEntry) -> Unit) {
    val rows = remember(state.feed) { tvHomeRows(state.feed) }
    val initialItem = rows.firstOrNull()?.items?.firstOrNull()
    var focused by remember(initialItem) { mutableStateOf(initialItem) }
    val firstFocus = remember { FocusRequester() }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загружаем библиотеку…") }
        return
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("В списке «Смотрю» пока пусто", style = MaterialTheme.typography.headlineLarge)
            Button(onClick = onRefresh, modifier = Modifier.padding(top = 20.dp)) { Text("Обновить") }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        focused?.let { HeroBackground(it) }
        LazyColumn(
            contentPadding = PaddingValues(top = 300.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(rows, key = { it.title }) { row ->
                Column {
                    Text(row.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 52.dp, vertical = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 52.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(row.items, key = { "${it.kind}-${it.entry.anime.id}" }) { item ->
                            val isFirst = item == initialItem
                            TvPosterCard(
                                item = item,
                                modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                                onFocused = { focused = item },
                                onClick = { onAnime(item.entry) },
                            )
                        }
                    }
                }
            }
        }
        state.errorMessage?.let {
            Row(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text(it); Button(onClick = onRefresh, modifier = Modifier.padding(start = 12.dp)) { Text("Повторить") }
            }
        }
    }
    LaunchedEffect(initialItem) { if (initialItem != null) firstFocus.requestFocus() }
}

@Composable
private fun HeroBackground(item: FeedItem) {
    val anime = item.entry.anime
    Box(Modifier.fillMaxWidth().height(390.dp).animateContentSize()) {
        AsyncImage(
            model = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, MaterialTheme.colorScheme.background))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 52.dp).fillMaxWidth(0.48f)) {
            Text(anime.title, style = MaterialTheme.typography.displayMedium, maxLines = 2)
            Text("${item.episode} серия", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            anime.description?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun TvPosterCard(item: FeedItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (hasFocus) 1.08f else 1f, label = "tvCardScale")
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = 154.dp, height = 252.dp)
            .scale(scale)
            .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocused() }
            .then(if (hasFocus) Modifier.border(3.dp, KaeruAccent, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Column {
            Poster(item.entry.anime.posterUrl, item.entry.anime.title, Modifier.fillMaxWidth().height(216.dp))
            Text("${item.episode} серия", maxLines = 1, modifier = Modifier.padding(8.dp))
        }
    }
}
