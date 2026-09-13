package app.kaeru.ui.tv

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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.player.TvPlayerScreen

/** Nothing is playing. */
private const val NOTHING = 0

/** The one slot whose saved state outlives an episode. */
private const val SHELL = "shell"

@UnstableApi
@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    var code by remember { mutableStateOf("") }
    // Saved as ids rather than as anything richer: a domain entry is not parcelable, and what is
    // playing has to survive a process death on a device that is left switched on for days.
    var playingId by rememberSaveable { mutableIntStateOf(NOTHING) }
    var playingEpisode by rememberSaveable { mutableIntStateOf(NOTHING) }
    val shell = rememberSaveableStateHolder()

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
            true -> when {
                playingId != NOTHING -> TvPlayer(
                    animeId = playingId,
                    episode = playingEpisode,
                    onEpisode = { playingEpisode = it },
                    onExit = { playingId = NOTHING },
                )
                // The shell is taken down while an episode plays rather than drawn over, so that
                // the remote cannot walk out of the player and into rows nobody can see. What it
                // remembered — which destination, how far down a list, which card the D-pad was
                // on — is held here and handed back when the episode ends.
                else -> shell.SaveableStateProvider(SHELL) {
                    TvShell(
                        onPlay = { animeId, episode ->
                            playingId = animeId
                            playingEpisode = episode
                        },
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
