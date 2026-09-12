package app.kaeru.ui.mobile.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaeru.player.CastFramework
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.KaeruPlaybackService
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Playback gets its own activity: it is landscape, immersive and outlives nothing else in the
 * app, so it has no business sharing a window with the browsing screens.
 *
 * Rotation is declared as handled in the manifest, so turning the phone never tears the
 * surface down. Leaving the screen writes the position; only finishing it stops playback,
 * which is what lets audio carry on from the notification.
 */
@AndroidEntryPoint
class PlayerActivity : FragmentActivity() {

    /** Injected, not asked for from the screen: the route chooser needs a fragment manager. */
    @Inject lateinit var cast: CastFramework

    @Inject lateinit var castSessions: CastSessionBridge

    private val viewModel: PlayerViewModel by viewModels()
    private var target by mutableStateOf(0 to 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        goImmersive()
        target = read(intent)
        // Before anything plays, so the session sees playback start and can raise its
        // notification; a session created mid-playback may never hear a transition.
        startPlaybackService()
        // Idempotent, and armed from every screen that can cast: whichever the viewer reaches
        // first is the one that starts listening for receivers.
        castSessions.start()
        val castAvailable = cast.isAvailable
        setContent {
            KaeruTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val player by viewModel.videoPlayer.collectAsStateWithLifecycle()
                val view = LocalView.current
                val (animeId, episode) = target

                LaunchedEffect(animeId, episode) { if (animeId > 0) viewModel.start(animeId, episode) }
                // A remote control does not need the screen awake for twenty-four minutes.
                LaunchedEffect(state.isPlaying, state.isCasting) {
                    view.keepScreenOn = state.isPlaying && !state.isCasting
                }

                CompositionLocalProvider(LocalCastAvailable provides castAvailable) {
                    PlayerScreen(
                        state = state,
                        player = player,
                        onBack = { finish() },
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSeekTo = viewModel::seekTo,
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
                        onStopCasting = viewModel::stopCasting,
                        onConfirmCompleted = viewModel::confirmCompleted,
                        onDismissCompleted = viewModel::dismissCompleted,
                        onToastShown = viewModel::consumeToast,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = read(intent)
    }

    /** Whatever happened while this screen was away, it is the one asking now. */
    override fun onStart() {
        super.onStart()
        val (animeId, episode) = target
        if (animeId > 0) viewModel.start(animeId, episode)
    }

    /** Backgrounding is not stopping: the position is written down, the video carries on. */
    override fun onStop() {
        super.onStop()
        viewModel.reportProgress()
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.release()
            runCatching { stopService(Intent(this, KaeruPlaybackService::class.java)) }
        }
        super.onDestroy()
    }

    private fun read(intent: Intent?): Pair<Int, Int> =
        (intent?.getIntExtra(EXTRA_ANIME_ID, 0) ?: 0) to (intent?.getIntExtra(EXTRA_EPISODE, 1) ?: 1)

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Hands the session to the media service so headphones, the lock screen and Bluetooth can
     * drive it. Failing to start it costs the notification, never the video, so it never throws.
     */
    private fun startPlaybackService() {
        runCatching { startService(Intent(this, KaeruPlaybackService::class.java)) }
    }

    companion object {
        private const val EXTRA_ANIME_ID = "animeId"
        private const val EXTRA_EPISODE = "episode"

        fun intent(context: Context, animeId: Int, episode: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_EPISODE, episode)
    }
}
