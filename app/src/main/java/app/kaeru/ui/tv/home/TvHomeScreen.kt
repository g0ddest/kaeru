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
import androidx.compose.runtime.MutableState
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
import app.kaeru.ui.tv.requestFocusOrLog
import coil3.compose.AsyncImage

@Composable
fun TvHomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onAnime: (LibraryEntry) -> Unit,
) {
    val rows = remember(state.feed) { tvHomeRows(state.feed) }
    val initialItem = remember(state.feed) { initialTvItem(state.feed, rows) }
    var focused by remember(initialItem) { mutableStateOf(initialItem) }
    // Once per screen entry: a later background refresh (a Room emission changing `state.feed`)
    // must never re-grab focus from wherever the user has navigated to.
    val requestedInitialFocus = remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загружаем библиотеку…") }
        return
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("В списке «Смотрю» пока пусто", style = MaterialTheme.typography.headlineLarge)
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRefresh) { Text("Обновить") }
                Button(onClick = onLogout) { Text("Выйти") }
            }
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
                            TvPosterCard(
                                item = item,
                                isInitialFocusTarget = item == initialItem,
                                requestedInitialFocus = requestedInitialFocus,
                                onFocused = { focused = item },
                                onClick = { onAnime(item.entry) },
                            )
                        }
                    }
                }
            }
        }
        // One header strip above the rows so both actions are reachable with D-pad up.
        Row(
            Modifier.align(Alignment.TopEnd).padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.errorMessage?.let {
                Row(
                    Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it)
                    Button(onClick = onRefresh, modifier = Modifier.padding(start = 12.dp)) { Text("Повторить") }
                }
            }
            // Temporary entry point until plan 3 introduces a Settings screen.
            Button(onClick = onLogout) { Text("Выйти") }
        }
    }
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
private fun TvPosterCard(
    item: FeedItem,
    isInitialFocusTarget: Boolean,
    requestedInitialFocus: MutableState<Boolean>,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scale by animateFloatAsState(if (hasFocus) 1.08f else 1f, label = "tvCardScale")
    // The card claims initial focus itself: asking from the screen can run before the LazyRow has
    // composed this card, and that request then fails for good.
    LaunchedEffect(isInitialFocusTarget) {
        if (isInitialFocusTarget && !requestedInitialFocus.value) {
            requestedInitialFocus.value = true
            focusRequester.requestFocusOrLog("first card of the home feed")
        }
    }
    Card(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
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
