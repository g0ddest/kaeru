package app.kaeru.ui.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.waitingLabel
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.playerFailure
import app.kaeru.ui.common.player.skipLabel
import app.kaeru.ui.tv.claimFocusWhenReady
import app.kaeru.ui.tv.requestFocusOrLog
import kotlinx.coroutines.delay
import java.time.Instant

/** Long enough to read one line from a sofa. */
private const val TOAST_MS = 4_000L

/** How long the jog mark stays over the picture after the last press that moved it. */
private const val SEEK_MARK_MS = 900L

/**
 * The television player: the picture, and over it a panel that appears at the touch of any
 * button and leaves again on its own.
 *
 * The panel is two zones with the timeline drawn between them, as the spec asks — what is
 * playing above it, how it is playing below — and the D-pad walks that one vertical axis. With
 * the panel down the same D-pad drives the picture instead: left and right jog, and they leave
 * the panel where it is rather than covering the frames the viewer is hunting for.
 *
 * Nothing about what a press means is decided here. [TvPlayerKeyHandler] turns a key into a
 * command and [TvPanel] holds where the panel stands; this screen turns events into the one and
 * commands into calls.
 */
@UnstableApi
@Composable
fun TvPlayerScreen(
    state: PlayerUiState,
    player: Player?,
    onExit: () -> Unit,
    onPlayEpisode: (Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    /** The one button the marks put on the picture: past the opening, or on to the next episode. */
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onRetry: () -> Unit,
    onConfirmCompleted: () -> Unit,
    onDismissCompleted: () -> Unit,
    onToastShown: () -> Unit,
    /** «К списку серий» over an episode no voice has: leaves the player for the title's screen. */
    onBackToEpisodes: () -> Unit,
) {
    var panel by remember { mutableStateOf(TvPanel()) }
    var seek by remember { mutableStateOf<Long?>(null) }
    var seekAt by remember { mutableIntStateOf(0) }
    var tracksAsked by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val rungFocus = remember { TvPanelRung.entries.associateWith { FocusRequester() } }
    val skipFocus = remember { FocusRequester() }
    /** Whether the player itself holds the D-pad, which is what the claim below waits for. */
    var rootFocused by remember { mutableStateOf(false) }

    // A failure the viewer has answered with «Сменить озвучку»: the message steps aside for the
    // strip — but only once there is a strip to step aside for. [tvShowsFailure] is the rule.
    var choosingTrack by remember(state.errorMessage) { mutableStateOf(false) }
    val failed = tvShowsFailure(state, choosingTrack)
    // What the card says and offers, decided in the same place the phone decides it.
    val failure = remember(state.errorMessage, state.offline, state.failedReadingDownload, state.episodeUnavailable) {
        playerFailure(state)
    }
    val countdown = state.autoplayCountdownSec != null && state.nextEpisodeAvailable
    /** Something on screen is asking a question, and owns both the focus and the remote. */
    val cardOpen = failed || state.completedPrompt || countdown
    // A failure and the question about closing the show off both take the screen for themselves;
    // the autoplay offer stands above the panel, which is the one card that shares it. Nor is
    // there anything to control before the first frame: the poster is the whole screen until
    // there is an episode behind it.
    val panelShown = panel.visible && !failed && !state.completedPrompt && !state.isLoading
    /**
     * The skip button is on the picture and holding the remote.
     *
     * Never beside a card: a card is a question and this is a shortcut, and the one thing worse
     * than missing the shortcut is answering the question by accident.
     */
    val skipOffered = state.skip != null && !cardOpen && !state.isLoading
    val content = rememberTvPanelContent(state)
    val clock = rememberTvPlayerClock(state)
    val rungs = content.rungs
    // Nothing to drive before the first frame, so the D-pad is held the way a card holds it: the
    // centre cannot toggle a stream that has not started, and up and down go nowhere visible.
    // The skip button holds it too, for the ten seconds it is up: it is focusable and OK has to
    // reach it, where the handler would otherwise read the same press as «pause the picture».
    val remoteHeld = cardOpen || state.isLoading || skipOffered
    // Taken once per episode: the only thing measured against it is which day the next one airs.
    val now = remember(state.episode) { Instant.now() }

    fun perform(command: TvPlayerCommand?): Boolean = when (command) {
        null -> false
        // Already dealt with on the way in, where the wake could be timed; a key that only asks
        // for the panel back is left to the system, so volume and the like still reach it.
        is TvPlayerCommand.ShowPanel -> command.rung != null
        // The card that is up is the whole of the D-pad: a press that would walk out of it does
        // nothing rather than landing on a control the viewer cannot see past the card.
        TvPlayerCommand.KeepFocus -> true
        TvPlayerCommand.HidePanel -> { panel = panel.hidden(); true }
        is TvPlayerCommand.MoveRung -> {
            panel = panel.copy(rung = tvStepRung(rungs, panel.rung, command.down))
            true
        }
        TvPlayerCommand.TogglePlayPause -> { onTogglePlayPause(); true }
        is TvPlayerCommand.SeekBy -> {
            onSeekBy(command.deltaMs)
            // Only where the panel is not already showing the move on its own line.
            if (!panel.visible) {
                seek = command.deltaMs
                seekAt += 1
            }
            true
        }
        TvPlayerCommand.PlayNext -> { onNext(); true }
        TvPlayerCommand.Exit -> { onExit(); true }
    }

    // The voices cost a request, so they are asked for once — as soon as the view model knows
    // which anime this is, which is what a title arriving means.
    LaunchedEffect(state.title) {
        if (!tracksAsked && state.title.isNotEmpty()) {
            tracksAsked = true
            onLoadTranslations()
        }
    }
    LaunchedEffect(panel.visible, panel.wake, state.isPlaying, cardOpen, state.loadingTranslations) {
        if (panel.hidesItself(playing = state.isPlaying, asking = cardOpen || state.loadingTranslations)) {
            delay(PANEL_LINGER_MS)
            panel = panel.hidden()
        }
    }
    // The offer to move on belongs with the controls, so the panel comes back for it.
    LaunchedEffect(countdown) { if (countdown) panel = panel.shown() }
    // Whichever rung the D-pad is standing on is the one holding focus — and when nothing at all
    // wants it, the player itself takes it back so the next press still arrives.
    LaunchedEffect(panelShown, panel.rung, cardOpen, rungs, skipOffered) {
        when {
            cardOpen -> Unit
            // One press of OK is the whole point of the button, so it asks for the focus itself
            // — and when its ten seconds are up this same effect runs again and hands the focus
            // back to wherever it came from: the rung the panel is standing on, or the picture.
            skipOffered -> {
                withFrameNanos { }
                skipFocus.requestFocusOrLog("кнопку пропуска")
            }
            panelShown -> {
                // One frame of grace: a strip that has just appeared is still scrolling itself
                // to the episode in play, and asking a chip that has not composed yet for the
                // focus leaves the panel with none at all.
                withFrameNanos { }
                rungFocus.getValue(tvRungOrNearest(rungs, panel.rung)).requestFocusOrLog("панель плеера")
            }
            // Asked until it lands rather than once. This runs on the frame the panel leaves the
            // composition, and the chip that had the focus is going down with it — a single ask
            // can be made before the focus system has finished tidying that up, and then nothing
            // on screen holds the D-pad and the next press is spent getting it back. That was
            // back-with-the-strip-up leaving a picture no key seemed to reach.
            else -> rootFocus.claimFocusWhenReady("плеер") { rootFocused }
        }
    }
    LaunchedEffect(seekAt) {
        if (seek != null) {
            delay(SEEK_MARK_MS)
            seek = null
        }
    }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(TOAST_MS)
            onToastShown()
        }
    }

    // A television that sleeps through an episode is a television with a broken remote.
    val view = LocalView.current
    LaunchedEffect(state.isPlaying) { view.keepScreenOn = state.isPlaying }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }

    // The completion question is not here: it is a real dialog window, so back on it goes to its
    // own `onDismissRequest` and never reaches this handler.
    BackHandler {
        when {
            failed -> onExit()
            countdown -> onCancelAutoplay()
            // Against what is on screen rather than what the panel remembers: back undoes what
            // the viewer can see, and during the wait for the first frame that is nothing.
            else -> perform(
                TvPlayerKeyHandler.onKey(TvKey.BACK, KeyAction.DOWN, panelShown, state.isPlaying),
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                // Back is the dispatcher's to deliver, and it arrives through BackHandler.
                if (event.key == Key.Back || event.key == Key.Escape) return@onPreviewKeyEvent false
                val action = when (event.type) {
                    KeyEventType.KeyDown -> KeyAction.DOWN
                    KeyEventType.KeyUp -> KeyAction.UP
                    else -> return@onPreviewKeyEvent false
                }
                val key = tvKeyOf(event.key)
                val wasVisible = panel.visible
                val command = TvPlayerKeyHandler.onKey(
                    key = key,
                    action = action,
                    panelVisible = wasVisible,
                    isPlaying = state.isPlaying,
                    cardOpen = remoteHeld,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                )
                if (action == KeyAction.DOWN && wakesPanel(key, wasVisible)) {
                    panel = tvPanelAfterWake(panel, command, event.nativeKeyEvent.eventTime)
                    // The jog mark belongs to a clear picture. Press right and then up inside its
                    // second, and it would otherwise sit in the middle of the panel it was
                    // drawn to stand in for.
                    seek = null
                }
                perform(command)
            },
    ) {
        if (player != null) ContentFrame(player, Modifier.fillMaxSize())

        // Seconds of black with a spinner on it is indistinguishable from a set that has lost
        // its signal, so the wait for the first frame is the title's own poster instead.
        if (state.isLoading) TvFirstFrame(state)

        // What holds the focus while the controls are away, so the first press still arrives.
        Box(
            Modifier.fillMaxSize()
                .focusRequester(rootFocus)
                .focusProperties { canFocus = !panelShown && !cardOpen }
                .onFocusChanged { rootFocused = it.isFocused }
                .focusable(),
        )

        // Both stand in the middle of the picture and a jog is what makes the picture run dry,
        // so for the second after one the mark that says which way it went is the useful one.
        if (state.isBuffering && !state.isLoading && !failed && seek == null) {
            TvBufferingMark(Modifier.align(Alignment.Center))
        }
        seek?.let { TvSeekIndicator(it, Modifier.align(Alignment.Center)) }

        if (panelShown) TvPlayerHeader(state.title, Modifier.align(Alignment.TopStart))

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            // Whatever this episode running out means, said above the panel rather than inside
            // it: the panel is where the viewer chooses, and this is the app doing the asking.
            val card = Modifier
                .padding(end = PlayerGutter, bottom = if (panelShown) KaeruTokens.Space4 else KaeruTokens.Space8)
                .align(Alignment.End)
            when {
                failed || state.completedPrompt -> Unit
                // First for the same reason as on the phone: this corner carries one offer at a
                // time, and the opening's is minutes away from anything the end of the episode
                // has to say.
                skipOffered && state.skip != null -> TvSkipButton(
                    label = skipLabel(state.skip),
                    onSkip = onSkip,
                    modifier = card.then(Modifier.focusRequester(skipFocus)),
                )
                countdown -> TvAutoplayCard(
                    episode = state.episode + 1,
                    countdownSec = state.autoplayCountdownSec,
                    onNow = onNext,
                    onCancel = onCancelAutoplay,
                    modifier = card,
                )
                // Nothing is said about a show this device has no catalogue entry for: with no
                // aired count there is no telling «that was the last one» from «we do not know».
                // Nor about a finished one, where running out of episodes is the end of the story
                // and the question about closing it off is already the only one worth asking.
                state.episodeEnding && !state.nextEpisodeAvailable &&
                    state.availableEpisodes > 0 && state.moreEpisodesComing -> TvWaitingCard(
                    waiting = waitingLabel(
                        episode = state.episode + 1,
                        nextEpisodeAt = state.nextEpisodeAt,
                        aired = state.availableEpisodes,
                        now = now,
                    ),
                    modifier = card,
                )
            }

            if (panelShown) {
                TvPlayerPanel(
                    content = content,
                    clock = clock,
                    rungFocus = rungFocus,
                    onPickEpisode = onPlayEpisode,
                    onPickTranslation = { track ->
                        choosingTrack = false
                        onPickTranslation(track)
                    },
                    onPickQuality = onPickQuality,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekBy = onSeekBy,
                    onSkipIntro = onSkipIntro,
                    onNext = onNext,
                )
            }
        }

        if (failed && failure != null) {
            TvPlaybackFailure(
                failure = failure,
                onRetry = onRetry,
                onBackToEpisodes = onBackToEpisodes,
                onChangeTranslation = {
                    choosingTrack = true
                    if (!tracksAsked) {
                        tracksAsked = true
                        onLoadTranslations()
                    }
                    // Stored unclamped: the voices are exactly what is missing at this moment,
                    // so clamping here would settle on the transport row and stay there when the
                    // list arrives. The render-time clamp parks the D-pad somewhere reachable
                    // meanwhile and the focus effect moves it up the moment the rung exists.
                    panel = panel.shown(TvPanelRung.TRANSLATIONS)
                },
            )
        }

        if (state.completedPrompt) {
            TvCompletedDialog(state.title, onConfirm = onConfirmCompleted, onDismiss = onDismissCompleted)
        }

        // Clear of the title at one corner and of the panel along the bottom.
        state.toast?.let {
            TvPlayerToast(it, Modifier.align(Alignment.TopEnd).padding(PlayerGutter))
        }
    }
}

/** The remote, reduced to the keys the player has an opinion about. */
private fun tvKeyOf(key: Key): TvKey = when (key) {
    Key.DirectionLeft -> TvKey.LEFT
    Key.DirectionRight -> TvKey.RIGHT
    Key.DirectionUp -> TvKey.UP
    Key.DirectionDown -> TvKey.DOWN
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> TvKey.CENTER
    Key.Back, Key.Escape -> TvKey.BACK
    Key.MediaPlayPause -> TvKey.MEDIA_PLAY_PAUSE
    Key.MediaPlay -> TvKey.MEDIA_PLAY
    Key.MediaPause -> TvKey.MEDIA_PAUSE
    Key.MediaFastForward -> TvKey.MEDIA_FAST_FORWARD
    Key.MediaRewind -> TvKey.MEDIA_REWIND
    Key.MediaNext, Key.MediaSkipForward -> TvKey.MEDIA_NEXT
    Key.MediaStop -> TvKey.MEDIA_STOP
    else -> TvKey.OTHER
}
