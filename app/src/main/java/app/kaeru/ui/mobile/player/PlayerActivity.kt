package app.kaeru.ui.mobile.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.player.CastFramework
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.KaeruPlaybackService
import app.kaeru.ui.common.player.LocalCastAvailable
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.theme.KaeruTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    /** The one thing this screen remembers between launches: whether the question was put. */
    @Inject lateinit var notificationPrompt: PlaybackNotificationPrompt

    private val viewModel: PlayerViewModel by viewModels()
    private var target by mutableStateOf(0 to 1)

    /**
     * Registered as a field, which is before the activity is started, as the contract requires.
     * The answer changes nothing about playback: it is recorded so the question is put once.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            lifecycleScope.launch { notificationPrompt.markNotificationsAsked() }
        }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        goImmersive()
        target = read(intent)
        // Asked before the service is started, so a phone that says yes has the notification
        // from the first episode; nothing waits on the answer.
        askForNotifications()
        // Before anything plays, so the session sees playback start and can raise its
        // notification; a session created mid-playback may never hear a transition.
        startPlaybackService()
        // Idempotent, and armed from every screen that can cast: whichever the viewer reaches
        // first is the one that starts listening for receivers.
        castSessions.start()
        setContent {
            KaeruTheme {
                val castAvailable by cast.isAvailable.collectAsStateWithLifecycle()
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

    @OptIn(UnstableApi::class)
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
     * Puts the notification permission to the viewer, once, on the versions that require it.
     *
     * Android 13 and later hold a foreground service's notification back without it, which is
     * how an episode ended up playing on with no notification in the shade and no transport
     * control to stop it. Nothing here gates playback: the service starts either way, and a
     * refusal costs the notification and its controls, never the video. Headphone and Bluetooth
     * buttons keep working through the media session regardless.
     */
    private fun askForNotifications() {
        val granted = ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        lifecycleScope.launch {
            val asked = notificationPrompt.notificationsAsked()
            if (!shouldAskForNotifications(Build.VERSION.SDK_INT, granted, asked)) return@launch
            // A device with nothing to answer the request throws rather than refusing.
            runCatching { askNotifications.launch(POST_NOTIFICATIONS) }
        }
    }

    /**
     * Hands the session to the media service so headphones, the lock screen and Bluetooth can
     * drive it. Failing to start it costs the notification, never the video, so it never throws.
     */
    @OptIn(UnstableApi::class)
    private fun startPlaybackService() {
        runCatching { startService(Intent(this, KaeruPlaybackService::class.java)) }
    }

    companion object {
        private const val EXTRA_ANIME_ID = "animeId"
        private const val EXTRA_EPISODE = "episode"
        /**
         * Inlined on purpose: the name is a plain string that older platforms simply do not
         * know, and nothing ever asks for it there — [shouldAskForNotifications] is what keeps
         * the request on the versions that have it.
         */
        @SuppressLint("InlinedApi")
        private const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

        fun intent(context: Context, animeId: Int, episode: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_EPISODE, episode)
    }
}

/**
 * Whether to put the notification permission to the viewer: only where the platform withholds
 * it until asked, only while it is missing, and only once. A refusal is an answer — putting the
 * question again on every episode would be the worse bargain.
 */
internal fun shouldAskForNotifications(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked
