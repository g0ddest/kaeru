package app.kaeru.ui.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.ui.common.player.PlayerSheet
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.tv.requestFocusOrLog
import kotlinx.coroutines.delay

/** Four seconds of playing with nothing pressed and the controls get out of the picture. */
private const val PANEL_LINGER_MS = 4_000L

/** Long enough to read one line from a sofa. */
private const val TOAST_MS = 4_000L

/** How often a held key is allowed to restart the linger timer. */
private const val WAKE_THROTTLE_MS = 500L

/**
 * The television player: the picture, and over it a panel that appears at the touch of any
 * button and leaves again on its own.
 *
 * Everything the remote can mean lives in [TvPlayerKeyHandler]; this screen turns key events
 * into that vocabulary and commands into calls. The one piece of memory it keeps is where the
 * D-pad is standing, because "left" means scrub over the timeline and "previous button" inside
 * the row.
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
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onCloseSheet: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onRetry: () -> Unit,
    onConfirmCompleted: () -> Unit,
    onDismissCompleted: () -> Unit,
    onToastShown: () -> Unit,
) {
    var panelVisible by remember { mutableStateOf(true) }
    var wake by remember { mutableIntStateOf(0) }
    var panelFocus by remember { mutableStateOf(TvPlayerFocus.PROGRESS) }
    val rootFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }

    val failed = state.errorMessage != null
    /** A question is on screen and owns both the focus and the remote until it is answered. */
    val deciding = failed || state.completedPrompt
    val overlayOpen = deciding || state.sheet != null || state.autoplayCountdownSec != null
    // Nothing hides while it is being read, chosen from, or waited on.
    val holdPanel = overlayOpen || state.loadingTranslations

    // A strip is a chooser, and a chooser owns the D-pad whether or not a chip managed to take
    // focus — an empty list must not leave "back" meaning "leave the player".
    val focus = when {
        state.sheet != null -> TvPlayerFocus.STRIP
        !panelVisible -> TvPlayerFocus.NONE
        else -> panelFocus
    }

    // The last key that restarted the linger timer. A held button repeats twenty times a
    // second, and every restart would recompose the panel; once every half second is enough.
    val lastWake = remember { longArrayOf(0) }

    fun show(atMs: Long = Long.MAX_VALUE) {
        panelVisible = true
        if (atMs - lastWake[0] < WAKE_THROTTLE_MS) return
        lastWake[0] = atMs
        wake += 1
    }

    fun perform(command: TvPlayerCommand?): Boolean = when (command) {
        null -> false
        // The panel is already up by the time this is read; the key belongs to the system.
        TvPlayerCommand.ShowPanel -> false
        TvPlayerCommand.HidePanel -> { panelVisible = false; true }
        TvPlayerCommand.TogglePlayPause -> { onTogglePlayPause(); true }
        is TvPlayerCommand.SeekBy -> { onSeekBy(command.deltaMs); true }
        TvPlayerCommand.OpenEpisodes -> { onOpenTranslations(); true }
        TvPlayerCommand.OpenQuality -> { onOpenQualities(); true }
        TvPlayerCommand.CloseStrip -> { onCloseSheet(); true }
        TvPlayerCommand.FocusProgress -> { progressFocus.requestFocusOrLog("шкалу времени плеера"); true }
        TvPlayerCommand.FocusActions -> { actionsFocus.requestFocusOrLog("кнопки плеера"); true }
        TvPlayerCommand.PlayNext -> { onNext(); true }
        TvPlayerCommand.Exit -> { onExit(); true }
    }

    LaunchedEffect(panelVisible, wake, state.isPlaying, holdPanel) {
        if (panelVisible && state.isPlaying && !holdPanel) {
            delay(PANEL_LINGER_MS)
            panelVisible = false
        }
    }
    // The offer to move on belongs with the rest of the controls, so the panel comes back for it.
    LaunchedEffect(state.autoplayCountdownSec != null) { if (state.autoplayCountdownSec != null) show() }
    // The root is always composed, so this can never miss: whenever nothing else wants the
    // focus, the player itself takes it back and the remote keeps working.
    LaunchedEffect(panelVisible, overlayOpen) {
        if (!panelVisible && !overlayOpen) rootFocus.requestFocusOrLog("плеер")
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

    BackHandler {
        when {
            state.completedPrompt -> onDismissCompleted()
            failed -> onExit()
            else -> perform(TvPlayerKeyHandler.onKey(TvKey.BACK, KeyAction.DOWN, panelVisible, 0, focus))
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
                val wasVisible = panelVisible
                if (action == KeyAction.DOWN) show(event.nativeKeyEvent.eventTime)
                // A question on screen answers the remote itself: the D-pad walks its buttons.
                if (deciding) return@onPreviewKeyEvent false
                perform(
                    TvPlayerKeyHandler.onKey(
                        key = tvKeyOf(event.key),
                        action = action,
                        panelVisible = wasVisible,
                        repeatCount = event.nativeKeyEvent.repeatCount,
                        focusedControl = focus,
                    ),
                )
            },
    ) {
        if (player != null) ContentFrame(player, Modifier.fillMaxSize())

        // What holds the focus while the controls are away, so the first press still arrives.
        Box(
            Modifier.fillMaxSize()
                .focusRequester(rootFocus)
                .focusProperties { canFocus = !panelVisible && !overlayOpen }
                .focusable(),
        )

        if (state.isBuffering && !failed) TvBufferingMark(Modifier.align(Alignment.Center))

        if (panelVisible && !deciding) {
            TvPlayerPanel(
                state = state,
                overlayOpen = overlayOpen,
                progressFocus = progressFocus,
                actionsFocus = actionsFocus,
                onFocus = { panelFocus = it },
                onTogglePlayPause = onTogglePlayPause,
                onSeekBy = onSeekBy,
                onSkipIntro = onSkipIntro,
                onEpisodes = onOpenTranslations,
                onQualities = onOpenQualities,
                onNext = onNext,
            ) {
                PanelSlot(
                    state = state,
                    onPlayEpisode = { onCloseSheet(); onPlayEpisode(it) },
                    onPickTranslation = onPickTranslation,
                    onPickQuality = onPickQuality,
                    onNext = onNext,
                    onCancelAutoplay = onCancelAutoplay,
                    onFocusActions = { panelFocus = TvPlayerFocus.ACTIONS },
                )
            }
        }

        if (failed) {
            TvPlaybackFailure(
                message = state.errorMessage.orEmpty(),
                onRetry = onRetry,
                onChangeTranslation = onOpenTranslations,
            )
        }

        if (state.completedPrompt) {
            TvCompletedDialog(state.title, onConfirm = onConfirmCompleted, onDismiss = onDismissCompleted)
        }

        // Clear of the title at the top and of the panel at the bottom, wherever it is.
        state.toast?.let {
            TvPlayerToast(
                it,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (panelVisible && !deciding) 280.dp else 56.dp),
            )
        }
    }
}

/**
 * The rung of the panel above the timeline: whichever of the strips, the countdown or the wait
 * for a track list is asking for attention. Only one of them ever is.
 */
@Composable
private fun PanelSlot(
    state: PlayerUiState,
    onPlayEpisode: (Int) -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onFocusActions: () -> Unit,
) {
    when {
        state.sheet == PlayerSheet.TRANSLATIONS ->
            TvEpisodeStrip(state, onEpisode = onPlayEpisode, onTranslation = onPickTranslation)
        state.sheet == PlayerSheet.QUALITY -> TvQualityStrip(state, onQuality = onPickQuality)
        state.autoplayCountdownSec != null -> Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
            TvAutoplayCard(
                episode = state.episode + 1,
                countdownSec = state.autoplayCountdownSec,
                onNow = onNext,
                onCancel = onCancelAutoplay,
                onFocused = onFocusActions,
                modifier = Modifier.padding(end = 48.dp, bottom = 24.dp),
            )
        }
        state.loadingTranslations -> TvStripMessage("Загружаем озвучки…")
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
    // A remote with separate play and pause buttons is rare enough that one toggle serves all
    // three; the worst it can do is what the viewer was about to press anyway.
    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> TvKey.MEDIA_PLAY_PAUSE
    Key.MediaFastForward -> TvKey.MEDIA_FAST_FORWARD
    Key.MediaRewind -> TvKey.MEDIA_REWIND
    Key.MediaNext, Key.MediaSkipForward -> TvKey.MEDIA_NEXT
    else -> TvKey.OTHER
}
