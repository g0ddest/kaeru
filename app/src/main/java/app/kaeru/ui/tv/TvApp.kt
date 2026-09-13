package app.kaeru.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.auth.TvPairingViewModel
import app.kaeru.ui.tv.home.TvHomeScreen
import app.kaeru.ui.tv.player.TvPlayerScreen

/** No title card is open, and no episode is playing. */
private const val NOTHING = 0

@UnstableApi
@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    var code by remember { mutableStateOf("") }
    // Saved as ids rather than as the entries themselves: a `LibraryEntry` is not parcelable and
    // holding one across a process death would mean saving a copy of the catalogue. The feed
    // comes back from Room in a moment, and the title card is found in it again.
    var selectedId by rememberSaveable { mutableIntStateOf(NOTHING) }
    var playingId by rememberSaveable { mutableIntStateOf(NOTHING) }
    var playingEpisode by rememberSaveable { mutableIntStateOf(NOTHING) }

    KaeruTvTheme {
        when (auth.loggedIn) {
            null -> Box(Modifier.fillMaxSize())
            false -> {
                // The pairing port is opened here rather than in the view model's constructor: it
                // belongs to the screen, and the view model outlives it — `hiltViewModel()` on a
                // television hands out one scoped to the activity, so `onCleared` does not fire
                // when this branch is swapped for the home screen after a successful sign-in.
                val pairingViewModel: TvPairingViewModel = hiltViewModel()
                val pairing by pairingViewModel.uiState.collectAsStateWithLifecycle()
                DisposableEffect(pairingViewModel) {
                    pairingViewModel.start()
                    onDispose { pairingViewModel.stop() }
                }
                TvLoginScreen(
                    authorizeUrl = authViewModel.tvAuthorizeUrl,
                    state = auth,
                    pairing = pairing,
                    code = code,
                    onCode = { code = it },
                    onSubmit = { authViewModel.exchangeTvCode(code) },
                    onNewQr = pairingViewModel::start,
                )
            }
            true -> {
                // Hoisted above the branch: the feed is held by the ViewModel, so swapping the
                // home rows for the title card costs nothing and preserves the loaded state.
                val home: HomeViewModel = hiltViewModel()
                val homeState = home.uiState.collectAsStateWithLifecycle().value
                val selected = remember(homeState.feed, selectedId) {
                    if (selectedId == NOTHING) null else tvFeedItem(homeState.feed, selectedId)
                }
                // Only the title card's back is handled here: the player has its own, which hides
                // the panel before it lets go of the screen.
                BackHandler(enabled = playingId == NOTHING && selected != null) { selectedId = NOTHING }
                when {
                    // Leaving the player uncovers whatever it was opened from, because that
                    // screen was never taken down — only drawn over.
                    playingId != NOTHING -> TvPlayer(
                        animeId = playingId,
                        episode = playingEpisode,
                        onEpisode = { playingEpisode = it },
                        onExit = { playingId = NOTHING },
                    )
                    selected != null -> TvTitleCard(
                        item = selected,
                        onWatch = { playingId = selected.entry.anime.id; playingEpisode = it },
                        onClose = { selectedId = NOTHING },
                    )
                    else -> TvHomeScreen(
                        state = homeState,
                        onRefresh = home::refresh,
                        onLogout = authViewModel::logout,
                        // One press plays. The card opens only for an episode there is no point
                        // starting — one that has not aired — and for a deliberate long press.
                        onPlay = { item ->
                            when (val action = tvWatchAction(item)) {
                                is TvWatchAction.Play -> {
                                    playingId = item.entry.anime.id
                                    playingEpisode = action.episode
                                }
                                TvWatchAction.NotAired -> selectedId = item.entry.anime.id
                            }
                        },
                        onDetails = { selectedId = it.entry.anime.id },
                    )
                }
            }
        }
    }
}

/**
 * The player, sharing the activity's [PlayerViewModel] with nothing else on the television.
 *
 * Leaving writes the position down and then lets playback go, and merely stopping — Home
 * pressed mid-episode — pauses it: unlike the phone, there is no notification to carry on from
 * and no receiver to leave it playing on.
 */
@UnstableApi
@Composable
private fun TvPlayer(animeId: Int, episode: Int, onEpisode: (Int) -> Unit, onExit: () -> Unit) {
    val viewModel: PlayerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.videoPlayer.collectAsStateWithLifecycle()

    LaunchedEffect(animeId, episode) { viewModel.start(animeId, episode) }
    // Autoplay moves on without asking this screen, so the screen follows it. Without this the
    // episode restored after a process death would be the one the viewer started hours ago.
    // Starting what is already playing is a no-op, so the two effects cannot fight.
    LaunchedEffect(state.episode) {
        if (state.episode > 0 && state.episode != episode) onEpisode(state.episode)
    }
    // A television has nothing to carry playback once this screen stops: no media service, no
    // notification, no receiver, and a launcher does not take audio focus. Home pressed
    // mid-episode would otherwise leave the episode playing over it with no transport control
    // anywhere to stop it. Pausing keeps the episode loaded, so coming back resumes it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
