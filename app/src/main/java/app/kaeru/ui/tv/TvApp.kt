package app.kaeru.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kaeru.domain.model.FeedItem
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.home.TvHomeScreen
import app.kaeru.ui.tv.player.TvPlayerScreen

/** What the viewer is looking at. Three states, and back walks them in this order. */
private data class TvPlayback(val animeId: Int, val episode: Int)

@UnstableApi
@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FeedItem?>(null) }
    var playing by remember { mutableStateOf<TvPlayback?>(null) }
    // Only the title card's back is handled here: the player has its own, which hides the panel
    // before it lets go of the screen.
    BackHandler(enabled = playing == null && selected != null) { selected = null }
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
                // Hoisted above the branch: the feed is held by the ViewModel, so swapping the
                // home rows for the title card costs nothing and preserves the loaded state.
                val home: HomeViewModel = hiltViewModel()
                val homeState = home.uiState.collectAsStateWithLifecycle().value
                val target = playing
                val entry = selected
                when {
                    // Leaving the player uncovers whatever it was opened from, because that
                    // screen was never taken down — only drawn over.
                    target != null -> TvPlayer(
                        target = target,
                        onEpisode = { playing = target.copy(episode = it) },
                        onExit = { playing = null },
                    )
                    entry != null -> TvTitleCard(
                        item = entry,
                        onWatch = { playing = TvPlayback(entry.entry.anime.id, it) },
                        onClose = { selected = null },
                    )
                    else -> TvHomeScreen(
                        state = homeState,
                        onRefresh = home::refresh,
                        onLogout = authViewModel::logout,
                        // One press plays. The card opens only for an episode there is no point
                        // starting — one that has not aired — and for a deliberate long press.
                        onPlay = { item ->
                            when (val action = tvWatchAction(item)) {
                                is TvWatchAction.Play -> playing = TvPlayback(item.entry.anime.id, action.episode)
                                TvWatchAction.NotAired -> selected = item
                            }
                        },
                        onDetails = { selected = it },
                    )
                }
            }
        }
    }
}

/**
 * The player, sharing the activity's [PlayerViewModel] with nothing else on the television.
 *
 * Leaving writes the position down and then lets playback go: unlike the phone, there is no
 * notification to carry on from and no receiver to leave it playing on.
 */
@UnstableApi
@Composable
private fun TvPlayer(target: TvPlayback, onEpisode: (Int) -> Unit, onExit: () -> Unit) {
    val viewModel: PlayerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.videoPlayer.collectAsStateWithLifecycle()

    LaunchedEffect(target) { viewModel.start(target.animeId, target.episode) }

    TvPlayerScreen(
        state = state,
        player = player,
        onExit = {
            viewModel.reportProgress()
            viewModel.release()
            onExit()
        },
        // Routed through the caller so the screen's target and the controller agree on which
        // episode is playing; the effect above is what actually starts it.
        onPlayEpisode = onEpisode,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSeekBy = viewModel::seekBy,
        onSkipIntro = viewModel::skipIntro,
        onNext = viewModel::playNext,
        onCancelAutoplay = viewModel::cancelAutoplay,
        onOpenTranslations = viewModel::openTranslations,
        onOpenQualities = viewModel::openQualities,
        onCloseSheet = viewModel::closeSheet,
        onPickTranslation = viewModel::pickTranslation,
        onPickQuality = viewModel::pickQuality,
        onRetry = viewModel::retry,
        onConfirmCompleted = viewModel::confirmCompleted,
        onDismissCompleted = viewModel::dismissCompleted,
        onToastShown = viewModel::consumeToast,
    )
}

@Composable
private fun TvTitleCard(item: FeedItem, onWatch: (Int) -> Unit, onClose: () -> Unit) {
    val entry = item.entry
    val primary = remember { FocusRequester() }
    val action = remember(item) { tvWatchAction(item) }
    LaunchedEffect(entry.anime.id) { primary.requestFocusOrLog("главную кнопку карточки тайтла") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxSize(0.72f)) {
            Row(Modifier.padding(36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Poster(entry.anime.posterUrl, entry.anime.title, Modifier.weight(0.34f))
                Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(entry.anime.title, style = MaterialTheme.typography.displaySmall)
                    Text("Просмотрено ${entry.rate.episodes} из ${entry.anime.availableEpisodes}")
                    if (action is TvWatchAction.NotAired) Text("Следующая серия ещё не вышла")
                    entry.anime.description?.let { Text(it, maxLines = 8) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (action is TvWatchAction.Play) {
                            Button(
                                onClick = { onWatch(action.episode) },
                                modifier = Modifier.focusRequester(primary),
                            ) { Text(action.label) }
                        }
                        Button(
                            onClick = onClose,
                            modifier = if (action is TvWatchAction.Play) Modifier else Modifier.focusRequester(primary),
                        ) { Text("Назад") }
                    }
                }
            }
        }
    }
}
