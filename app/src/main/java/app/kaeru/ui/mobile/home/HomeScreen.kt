package app.kaeru.ui.mobile.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.FeedItem
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.ProgressStrip
import app.kaeru.ui.common.Skeleton
import app.kaeru.ui.common.home.HomeUiState
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onRefresh: () -> Unit, onAnime: (Int) -> Unit) {
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        when {
            state.isLoading -> HomeSkeleton()
            state.feed.isEmpty -> EmptyHome(onRefresh)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.feed.top?.let { top -> item(key = "hero") { Hero(top, onAnime) } }
                state.errorMessage?.let { message ->
                    item(key = "error") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            Button(onClick = onRefresh) { Text("Повторить") }
                        }
                    }
                }
                if (state.feed.newEpisodes.isNotEmpty()) item { FeedRow("Новые серии", state.feed.newEpisodes, onAnime) }
                if (state.feed.continueWatching.isNotEmpty()) item { FeedRow("Продолжить", state.feed.continueWatching, onAnime) }
                if (state.feed.nextUp.isNotEmpty()) item { FeedRow("Следующая серия", state.feed.nextUp, onAnime) }
                if (state.feed.upcoming.isNotEmpty()) item { FeedRow("Скоро", state.feed.upcoming, onAnime) }
                if (state.feed.planned.isNotEmpty()) item { FeedRow("В планах", state.feed.planned, onAnime) }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun Hero(item: FeedItem, onAnime: (Int) -> Unit) {
    val anime = item.entry.anime
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(anime.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            Text("${item.episode} серия", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { onAnime(anime.id) }) { Text("Подробнее") }
        }
    }
}

@Composable
private fun FeedRow(title: String, feed: List<FeedItem>, onAnime: (Int) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(feed, key = { "${it.kind}-${it.entry.anime.id}" }) { item ->
                Column(Modifier.size(width = 132.dp, height = 230.dp).clickable { onAnime(item.entry.anime.id) }) {
                    Poster(item.entry.anime.posterUrl, item.entry.anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    item.entry.progressFraction(0.9f)?.let { ProgressStrip(it) }
                    Text(item.entry.anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable private fun HomeSkeleton() = Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Skeleton(Modifier.fillMaxWidth().height(300.dp)); Skeleton(Modifier.fillMaxWidth().height(24.dp)); Skeleton(Modifier.fillMaxWidth().height(200.dp))
}

@Composable private fun EmptyHome(onRefresh: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally,
) {
    Text("В списке «Смотрю» пока пусто", style = MaterialTheme.typography.titleLarge)
    Text("Добавьте аниме на Shikimori и обновите список.", modifier = Modifier.padding(12.dp))
    Button(onClick = onRefresh) { Text("Обновить") }
}
