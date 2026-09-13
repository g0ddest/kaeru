package app.kaeru.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthUiState
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.auth.TvPairingViewModel
import app.kaeru.ui.tv.player.TvPlayerScreen

/** The three things a television can be showing before any of them has a screen of its own. */
internal enum class TvScreen { LOADING, LOGIN, APP }

/**
 * Which of them, from the account alone.
 *
 * Nothing else is allowed a say. A phone signing this television in flips `loggedIn` to true while
 * the login screen still holds whatever the last typed attempt left on the state, and the screen
 * has to go the moment the account exists rather than once that debris is tidied away.
 */
internal fun tvScreen(auth: AuthUiState): TvScreen = when (auth.loggedIn) {
    null -> TvScreen.LOADING
    false -> TvScreen.LOGIN
    true -> TvScreen.APP
}

/** No title card is open, and no episode is playing. */
private const val NOTHING = 0

/** The one slot whose saved state outlives an episode. */
private const val SHELL = "shell"

@UnstableApi
@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    val code by authViewModel.tvCode.collectAsStateWithLifecycle()
    // Saved as ids rather than as anything richer: a domain entry is not parcelable, and what is
    // playing has to survive a process death on a device that is left switched on for days.
    var playingId by rememberSaveable { mutableIntStateOf(NOTHING) }
    var playingEpisode by rememberSaveable { mutableIntStateOf(NOTHING) }
    val shell = rememberSaveableStateHolder()

    KaeruTvTheme {
        when (tvScreen(auth)) {
            TvScreen.LOADING -> Box(Modifier.fillMaxSize())
            TvScreen.LOGIN -> {
                // The pairing port is opened here rather than in the view model's constructor: it
                // belongs to the screen, and the view model outlives it — `hiltViewModel()` on a
                // television hands out one scoped to the activity, so `onCleared` does not fire
                // when this branch is swapped for the home screen after a successful sign-in.
                //
                // Keyed on the screen being *visible* rather than merely composed. Home pressed on
                // the remote leaves this composition alive, and a port left bound behind a
                // launcher is a port listening for a code whose QR nobody can see.
                val pairingViewModel: TvPairingViewModel = hiltViewModel()
                val pairing by pairingViewModel.uiState.collectAsStateWithLifecycle()
                LifecycleStartEffect(pairingViewModel) {
                    pairingViewModel.start()
                    onStopOrDispose { pairingViewModel.stop() }
                }
                TvLoginScreen(
                    authorizeUrl = authViewModel.tvAuthorizeUrl,
                    state = auth,
                    pairing = pairing,
                    code = code,
                    onCode = authViewModel::setTvCode,
                    onSubmit = authViewModel::exchangeTvCode,
                    onNewQr = pairingViewModel::start,
                )
            }
            TvScreen.APP -> when {
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

    // Explicit, and deliberately so: unlike the phone's intent, this key cannot go stale — the
    // effect below walks the route on to whatever autoplay reached, so every value that arrives
    // here is either the viewer picking an episode or the route agreeing with the session. A
    // non-explicit start would attach instead of playing, and the episode grid would stop working.
    LaunchedEffect(animeId, episode) { viewModel.start(animeId, episode, explicit = true) }
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
