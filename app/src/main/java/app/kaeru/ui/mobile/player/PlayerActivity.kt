package app.kaeru.ui.mobile.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
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
import app.kaeru.R
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

    /** Whether the picture is in a floating window right now, which is all the screen needs to know. */
    private var inPictureInPicture by mutableStateOf(false)

    /** Whether this device has floating windows at all; some do not, and the button must not lie. */
    private val supportsPictureInPicture: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val windowMode = Consumer<PictureInPictureModeChangedInfo> { info ->
        inPictureInPicture = info.isInPictureInPictureMode
    }

    /**
     * The two controls the floating window has room for. They arrive as broadcasts because that
     * is the only thing a [RemoteAction] can carry; the filter is registered for this app alone,
     * so nothing outside it can press them.
     */
    private val windowControls = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(EXTRA_WINDOW_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> viewModel.togglePlayPause()
                CONTROL_NEXT -> viewModel.playNext()
            }
        }
    }

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
        addOnPictureInPictureModeChangedListener(windowMode)
        ContextCompat.registerReceiver(
            this,
            windowControls,
            IntentFilter(ACTION_WINDOW_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
                // The system holds these until the window is asked for, which on Android 12 and
                // later is the moment the viewer swipes home — far too late to be computing them.
                LaunchedEffect(state.isPlaying, state.isCasting, state.nextEpisodeAvailable, state.errorMessage, state.episode) {
                    describeWindow()
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
                        isInPictureInPicture = inPictureInPicture,
                        onEnterPictureInPicture = ::enterWindow.takeIf { supportsPictureInPicture },
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

    /**
     * The viewer is leaving the app with an episode playing. On Android 12 and later the system
     * folds the window itself from the parameters set above; below that this is the only notice
     * given, and it arrives before the activity is stopped.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        if (!pipPlan(viewModel.uiState.value).autoEnter) return
        enterWindow()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(windowMode)
        runCatching { unregisterReceiver(windowControls) }
        if (isFinishing) {
            viewModel.release()
            runCatching { stopService(Intent(this, KaeruPlaybackService::class.java)) }
        }
        super.onDestroy()
    }

    /**
     * Folds the picture into a floating window now.
     *
     * Nothing is stopped and nothing is saved: the activity stays started, so progress keeps
     * being written and the episode keeps being counted exactly as it was full screen. Closing
     * the window finishes the activity, and [onDestroy] takes the picture down and writes the
     * position the way it does for the back button.
     */
    private fun enterWindow() {
        if (!supportsPictureInPicture) return
        val plan = pipPlan(viewModel.uiState.value)
        if (!plan.allowed) return
        // A device that refuses the window is not a device that should lose the episode.
        runCatching { enterPictureInPictureMode(windowParams(plan)) }
    }

    /** Keeps the system's idea of the window in step with what is playing. */
    private fun describeWindow() {
        if (!supportsPictureInPicture) return
        runCatching { setPictureInPictureParams(windowParams(pipPlan(viewModel.uiState.value))) }
    }

    private fun windowParams(plan: PipPlan): PictureInPictureParams {
        val size = viewModel.videoPlayer.value?.videoSize
        val aspect = pipAspect(size?.width ?: 0, size?.height ?: 0)
        val decor = window.decorView
        val bounds = pipSourceBounds(decor.width, decor.height, size?.width ?: 0, size?.height ?: 0)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.width, aspect.height))
            // Where the window animates out of. Without it the picture appears to jump from the
            // whole screen, bars included, which is the part of the transition that looks broken.
            .setSourceRectHint(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            .setActions(windowActions(plan))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(plan.autoEnter)
        return builder.build()
    }

    /**
     * Two at most. A floating window is a few centimetres across and the platform shows three
     * actions at the outside; play and the next episode are the two worth having there, and the
     * second only where there is an episode to go to.
     */
    private fun windowActions(plan: PipPlan): List<RemoteAction> {
        val playPause = if (plan.playing) {
            windowAction(R.drawable.ic_pip_pause, "Пауза", CONTROL_PLAY_PAUSE)
        } else {
            windowAction(R.drawable.ic_pip_play, "Продолжить", CONTROL_PLAY_PAUSE)
        }
        val next = windowAction(R.drawable.ic_pip_next, "Следующая серия", CONTROL_NEXT)
        return if (plan.showNext) listOf(playPause, next) else listOf(playPause)
    }

    private fun windowAction(icon: Int, label: String, control: Int): RemoteAction {
        val intent = Intent(ACTION_WINDOW_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_WINDOW_CONTROL, control)
        val pending = PendingIntent.getBroadcast(
            this,
            control,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
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

        /** Registered for this app only, so nothing outside it can drive the floating window. */
        private const val ACTION_WINDOW_CONTROL = "app.kaeru.player.WINDOW_CONTROL"
        private const val EXTRA_WINDOW_CONTROL = "control"
        private const val CONTROL_PLAY_PAUSE = 1
        private const val CONTROL_NEXT = 2
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
