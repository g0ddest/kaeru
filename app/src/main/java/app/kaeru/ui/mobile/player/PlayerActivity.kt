package app.kaeru.ui.mobile.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import app.kaeru.domain.playback.PlaybackNotificationPrompt
import app.kaeru.player.CastFramework
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.KaeruPlaybackService
import app.kaeru.R
import app.kaeru.ui.common.player.LocalCastAvailable
import app.kaeru.ui.common.player.PlayerViewModel
import app.kaeru.ui.common.together.TogetherViewModel
import app.kaeru.domain.together.VoiceCapture
import app.kaeru.domain.together.VoicePlayback
import app.kaeru.ui.mobile.together.TogetherControls
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

    /** The microphone and the speaker a shared viewing talks through, named by their interfaces. */
    @Inject lateinit var voiceCapture: VoiceCapture

    @Inject lateinit var voicePlayback: VoicePlayback

    private val viewModel: PlayerViewModel by viewModels()

    /**
     * The session as this screen sees it.
     *
     * Its own view model rather than a field on the player's: playback and the conversation over
     * it fail, wait and end independently, and the session outlives this activity inside a
     * singleton that the join screen in the other activity is looking at too.
     */
    private val together: TogetherViewModel by viewModels()
    private var launch by mutableStateOf(Launch())

    /** Numbers the launches, so two of the same episode are still two launches. See [Launch.seq]. */
    private var launches = 0

    /** The launch already handed over, so a second delivery of it is a return rather than a choice. */
    private var delivered: Launch? = null

    /** Whether the picture is in a floating window right now, which is all the screen needs to know. */
    private var inPictureInPicture by mutableStateOf(false)

    /**
     * Something of the system's is up over the picture and has not been answered: the share
     * chooser, or the microphone permission.
     *
     * Neither is an activity of this app's, so nothing in the player's own state would know about
     * them. Both take the viewer out of the app mid-decision, and a host who picks a messenger to
     * send the invitation through should arrive in it rather than behind a floating window of the
     * episode they were watching. Cleared in [onResume], the one thing every way out has in common.
     */
    private var promptUp by mutableStateOf(false)

    /** Whether this device has floating windows at all; some do not, and the button must not lie. */
    private val supportsPictureInPicture: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * The shape of the picture, which decides the shape of the floating window.
     *
     * Held here rather than read from the player when the window is asked for: on Android 12 and
     * later the system reads the parameters it was last given, at the moment the viewer swipes
     * home, so anything computed lazily then is computed too late. Media3 announces this through
     * a listener and nothing else, hence the field.
     */
    private var videoSize by mutableStateOf(VideoSize.UNKNOWN)

    private val windowMode = Consumer<PictureInPictureModeChangedInfo> { info ->
        inPictureInPicture = info.isInPictureInPictureMode
        // Expanding back to full screen is not a change the activity is rebuilt for, and the
        // hidden state of the system bars does not reliably survive the transition on every
        // build. Idempotent, so saying it again on the way out costs nothing.
        if (!info.isInPictureInPictureMode) goImmersive()
    }

    /**
     * The two controls the floating window has room for, and the broadcast that carries a press
     * back here — the only thing a [RemoteAction] can be made of. See [WindowControls], where the
     * registration and the intents that Android 14 is particular about are tested.
     */
    private val windowControls by lazy {
        WindowControls(
            context = this,
            onPlayPause = { viewModel.togglePlayPause() },
            onNext = { viewModel.playNext() },
        )
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
        launch = read(intent, isExplicitLaunch(recreated = savedInstanceState != null, intentFlags = intent.flags))
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
        windowControls.register()
        setContent {
            KaeruTheme {
                val castAvailable by cast.isAvailable.collectAsStateWithLifecycle()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val player by viewModel.videoPlayer.collectAsStateWithLifecycle()
                val session by together.uiState.collectAsStateWithLifecycle()
                val view = LocalView.current
                val opened = launch

                // Keyed on the launch itself, never on what is playing: an episode that changes
                // under the screen — autoplay moving on — must not look like a new launch and
                // start the old one again.
                LaunchedEffect(opened) { deliver(opened.explicit) }
                // A remote control does not need the screen awake for twenty-four minutes.
                LaunchedEffect(state.isPlaying, state.isCasting) {
                    view.keepScreenOn = state.isPlaying && !state.isCasting
                }
                // The one thing Media3 announces rather than publishes: without a listener the
                // video's shape is whatever it was before the first frame was decoded, and the
                // window folds 4:3 episodes into a 16:9 box.
                DisposableEffect(player) {
                    val listening = player
                    if (listening == null) {
                        videoSize = VideoSize.UNKNOWN
                        onDispose {}
                    } else {
                        videoSize = listening.videoSize
                        val listener = object : Player.Listener {
                            override fun onVideoSizeChanged(size: VideoSize) {
                                videoSize = size
                            }
                        }
                        listening.addListener(listener)
                        onDispose { listening.removeListener(listener) }
                    }
                }
                // The system holds these until the window is asked for, which on Android 12 and
                // later is the moment the viewer swipes home — far too late to be computing them.
                // The video's shape is a key, so the window is re-described the moment it is known;
                // the decor's own bounds settle at the first layout, which is inside that.
                LaunchedEffect(
                    state.isPlaying,
                    state.isCasting,
                    state.nextEpisodeAvailable,
                    state.errorMessage,
                    state.episode,
                    state.sheet,
                    state.completedPrompt,
                    state.pipOnLeave,
                    session.historyOpen,
                    promptUp,
                    videoSize,
                ) {
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
                        onRememberQuality = viewModel::setRememberQuality,
                        onPickEpisode = viewModel::playEpisode,
                        onRetry = viewModel::retry,
                        onStopCasting = viewModel::stopCasting,
                        onConfirmCompleted = viewModel::confirmCompleted,
                        onDismissCompleted = viewModel::dismissCompleted,
                        onToastShown = viewModel::consumeToast,
                        onDownload = viewModel::download,
                        onRemoveDownload = viewModel::removeDownload,
                        onRemoveBrokenDownload = viewModel::removeDownloadAndRetry,
                        isInPictureInPicture = inPictureInPicture,
                        onEnterPictureInPicture = ::enterWindow.takeIf { supportsPictureInPicture },
                        together = TogetherControls(
                            state = session,
                            recorder = voiceCapture,
                            player = voicePlayback,
                            // The title and the episode go into the message to the friend, so the
                            // invitation is built from what is actually on screen rather than from
                            // what the intent asked for an hour ago.
                            onShare = { together.share(state.title, state.episode) },
                            onLeave = together::leave,
                            onShareShown = together::shareShown,
                            onSendChat = together::sendChat,
                            onReaction = together::sendReaction,
                            onVoice = together::sendVoice,
                            onMicDenied = together::microphoneDenied,
                            onOpenHistory = together::openHistory,
                            onCloseHistory = together::closeHistory,
                            onReplay = together::replay,
                            onClipPlayed = together::clipPlayed,
                            onLeaveWait = together::leaveWait,
                            onMessageShown = together::messageShown,
                            onPlayerAttached = together::playerAttached,
                            onAutoHide = together::setAutoHide,
                            onSystemPrompt = ::systemPrompt,
                            enabled = true,
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = read(intent, isExplicitLaunch(recreated = false, intentFlags = intent.flags))
    }

    /**
     * Back into view. Never a choice, whatever brought this screen here the first time: the
     * episode named by the intent may be three episodes and an hour behind what is playing, and
     * only a session with nothing in it is started from it.
     */
    override fun onStart() {
        super.onStart()
        // A choice not yet handed over belongs to the effect above, which knows it was one; this
        // runs first on the way in, and delivering it here would start the session the viewer is
        // leaving before starting the episode they asked for.
        if (launch.explicit && delivered != launch) return
        deliver(explicit = false)
    }

    /**
     * In front again, so whatever was over the picture has been answered, dismissed or left.
     *
     * Every way out of a dialog that was actually drawn comes back through here, including the
     * ones that are not an answer — the back button, a tap outside — which is why the flag is
     * cleared here as well as in the permission's own result callback, which only an answer
     * reaches. The share chooser has nothing but this: it is started as a new task and always
     * pauses this activity, so there is no case where it goes up and this never runs.
     */
    override fun onResume() {
        super.onResume()
        systemPrompt(up = false)
    }

    /** Hands the launch to the view model, remembering that it has now been made. */
    private fun deliver(explicit: Boolean) {
        val current = launch
        delivered = current
        // The Cast framework builds the notification's intent itself, so it carries no anime and
        // no episode. Whatever is playing is what the viewer tapped it about — and if nothing is,
        // there is no remote control to draw and the app is a better place to be than an empty one.
        if (current.animeId <= 0) {
            if (!viewModel.attachLive()) finish()
            return
        }
        // The position only ever rides on a choice: the same intent comes back out of recents and
        // through a rebuild, hours later, and by then it names where a friend was, not is.
        viewModel.start(current.animeId, current.episode, explicit, current.startPositionMs.takeIf { explicit })
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
        if (!windowPlan().autoEnter) return
        enterWindow()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(windowMode)
        windowControls.unregister()
        if (isFinishing) {
            // The shared viewing goes with the player, and only when the player is going for good:
            // a rotation, a trip to the background and a floating window all leave it running.
            together.playerGone(finishing = true, changingConfigurations = isChangingConfigurations)
            viewModel.release()
            runCatching { stopService(Intent(this, KaeruPlaybackService::class.java)) }
        }
        super.onDestroy()
    }

    /**
     * A chooser or a permission question going up over the picture, or coming back down.
     *
     * The parameters are re-sent from here rather than left to the effect above: on Android 12
     * and later the system reads whatever it was last given at the moment the task switches, and
     * the chooser starts in the same frame as the flag being raised.
     *
     * Told when it comes down as well as when it goes up. A permission the app already holds is
     * answered by the contract itself, with no dialog and no trip out of the app — so [onResume],
     * which every other way out comes back through, is never called and would leave the question
     * standing for the life of the activity with the window quietly disabled behind it.
     */
    private fun systemPrompt(up: Boolean) {
        if (promptUp == up) return
        promptUp = up
        describeWindow()
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
        val plan = windowPlan()
        if (!plan.allowed) return
        // A device that refuses the window is not a device that should lose the episode.
        runCatching { enterPictureInPictureMode(windowParams(plan)) }
    }

    /** Keeps the system's idea of the window in step with what is playing. */
    private fun describeWindow() {
        if (!supportsPictureInPicture) return
        runCatching { setPictureInPictureParams(windowParams(windowPlan())) }
    }

    /**
     * What the window should do, from everything that has a say in it — including the two things
     * the player's own state has never heard of: the session's history sheet, and a system
     * question this screen put up.
     */
    private fun windowPlan(): PipPlan = pipPlan(
        state = viewModel.uiState.value,
        historyOpen = together.uiState.value.historyOpen,
        promptUp = promptUp,
    )

    private fun windowParams(plan: PipPlan): PictureInPictureParams {
        val size = videoSize
        val aspect = pipAspect(size.width, size.height)
        val decor = window.decorView
        val bounds = pipSourceBounds(decor.width, decor.height, size.width, size.height)
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
            windowAction(R.drawable.ic_pip_pause, "Пауза", WindowControls.CONTROL_PLAY_PAUSE)
        } else {
            windowAction(R.drawable.ic_pip_play, "Продолжить", WindowControls.CONTROL_PLAY_PAUSE)
        }
        val next = windowAction(R.drawable.ic_pip_next, "Следующая серия", WindowControls.CONTROL_NEXT)
        return if (plan.showNext) listOf(playPause, next) else listOf(playPause)
    }

    private fun windowAction(icon: Int, label: String, control: Int): RemoteAction =
        RemoteAction(Icon.createWithResource(this, icon), label, label, windowControls.action(control))

    private fun read(intent: Intent?, explicit: Boolean) = readLaunch(intent, explicit, ++launches)

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
        /**
         * Inlined on purpose: the name is a plain string that older platforms simply do not
         * know, and nothing ever asks for it there — [shouldAskForNotifications] is what keeps
         * the request on the versions that have it.
         */
        @SuppressLint("InlinedApi")
        private const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

        /**
         * @param startPositionMs where to open the episode, instead of where this device left it.
         *   Joining a friend names theirs; everything else leaves it out and resumes as usual.
         */
        fun intent(context: Context, animeId: Int, episode: Int, startPositionMs: Long? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_EPISODE, episode)
                .apply { if (startPositionMs != null) putExtra(EXTRA_POSITION, startPositionMs) }
    }
}

/**
 * What this screen was opened with, and whether opening it was a choice.
 *
 * The episode is what the intent said at the moment it was built; [explicit] is what says whether
 * that number is still allowed to overrule a session that has moved on since.
 */
internal data class Launch(
    val animeId: Int = 0,
    val episode: Int = 1,
    val explicit: Boolean = false,
    /**
     * Where to open the episode, when the screen that opened it knew better than this device's
     * own row — a friend's position, on joining them. Null, not zero, when nothing was said: zero
     * is a position too, and would silence the episode's own resume.
     */
    val startPositionMs: Long? = null,
    /**
     * Which launch this is, counted from one.
     *
     * A launch is an event, not a value, and everything downstream compares it structurally — the
     * effect that delivers it and the guard that decides whether it has been delivered already.
     * Without this, asking for episode 7 a second time while the session sits on episode 9 would
     * read as the launch already made, be downgraded to a return, and attach to 9.
     */
    val seq: Int = 0,
)

/** Reads a launch out of [intent]. [seq] is what makes each read a launch of its own. */
internal fun readLaunch(intent: Intent?, explicit: Boolean, seq: Int) = Launch(
    animeId = intent?.getIntExtra(EXTRA_ANIME_ID, 0) ?: 0,
    episode = intent?.getIntExtra(EXTRA_EPISODE, 1) ?: 1,
    explicit = explicit,
    // Only a choice carries a position. The same intent comes back out of recents and through a
    // rebuild, hours later, still naming where a friend was when the invitation was pressed.
    startPositionMs = intent
        ?.takeIf { explicit && it.hasExtra(EXTRA_POSITION) }
        ?.getLongExtra(EXTRA_POSITION, -1L)
        ?.takeIf { it >= 0 },
    seq = seq,
)

private const val EXTRA_ANIME_ID = "animeId"
private const val EXTRA_EPISODE = "episode"
private const val EXTRA_POSITION = "positionMs"

/**
 * Whether a launch of the player is the viewer asking for an episode, or the same session coming
 * back into view.
 *
 * Two things mean «came back»: the system rebuilt the activity from its own saved state, and the
 * launch came out of recents rather than from a screen. Both arrive carrying the intent the player
 * was first opened with, whose episode may be several behind what is actually playing.
 */
internal fun isExplicitLaunch(recreated: Boolean, intentFlags: Int): Boolean =
    !recreated && (intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

/**
 * Whether to put the notification permission to the viewer: only where the platform withholds
 * it until asked, only while it is missing, and only once. A refusal is an answer — putting the
 * question again on every episode would be the worse bargain.
 */
internal fun shouldAskForNotifications(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked
