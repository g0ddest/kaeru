package app.kaeru.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.TranslationPickerSheet
import app.kaeru.ui.common.design.waitingLabel
import app.kaeru.ui.common.player.PlayerFailure
import app.kaeru.ui.common.player.PlayerRecovery
import app.kaeru.ui.common.player.PlayerSheet
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.playerFailure
import app.kaeru.ui.common.player.skipLabel
import app.kaeru.ui.common.together.TogetherCopy
import app.kaeru.ui.mobile.together.TogetherControls
import app.kaeru.ui.mobile.together.TogetherOverlay
import app.kaeru.ui.mobile.together.shareInvitation
import app.kaeru.ui.mobile.together.touchExploration
import kotlinx.coroutines.delay
import java.time.Instant

private const val CONTROLS_LINGER_MS = 3_000L
private const val PULSE_MS = 450L

/** How long the brightness or volume strip stays up after the finger leaves. */
private const val SWIPE_LINGER_MS = 700L

/**
 * The phone player: video edge to edge, everything else floating over it and getting out of
 * the way after three seconds.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    player: Player?,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    /** The one button the marks put on the picture: past the opening, or on to the next episode. */
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onCloseSheet: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onRememberQuality: (Boolean) -> Unit,
    onPickEpisode: (Int) -> Unit,
    onRetry: () -> Unit,
    onStopCasting: () -> Unit,
    onConfirmCompleted: () -> Unit,
    onDismissCompleted: () -> Unit,
    onToastShown: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRemoveBrokenDownload: () -> Unit,
    /** «К списку серий» over an episode no voice has: back to the title, which lists the season. */
    onBackToEpisodes: () -> Unit,
    isInPictureInPicture: Boolean = false,
    onEnterPictureInPicture: (() -> Unit)? = null,
    together: TogetherControls = TogetherControls(),
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // One call site for the surface, so it is the same node full screen and in a floating
        // window. Two would be two composition groups, and flipping between them disposes one
        // and creates the other — detaching and re-attaching the player's video output, which
        // is the picture blinking on the way into the window and again on the way out.
        if (!state.isCasting && player != null) ContentFrame(player, Modifier.fillMaxSize())

        // A clip plays once, straight away — and above the branch below, because a shared viewing
        // carries on in the floating window and a friend talking has to come out of the speaker
        // there too. Left inside it, the clip waited for the window to be expanded, the receipt
        // that ends the duck never arrived, and the episode stayed quiet for as long as the
        // window was open.
        //
        // Turning the episode down under the clip is not this screen's business: the view model
        // asks the player itself, which is the only thing that knows the volume to go back to —
        // and the only thing that can keep it down while the microphone is still held after the
        // clip ends.
        val clip = together.state.playing
        LaunchedEffect(clip) {
            val playing = clip ?: return@LaunchedEffect
            together.player?.play(playing.id, playing.bytes, together.onClipPlayed)
                ?: together.onClipPlayed()
        }
        DisposableEffect(together.player) {
            onDispose { together.player?.stop() }
        }

        // A floating window is a few centimetres of picture with the system's own two buttons
        // under it, and everything below would cover the episode rather than explain it. The
        // state behind it is remembered inside this branch, so it goes with the branch: leaving
        // the window puts the controls back for the viewer who just asked to see the episode.
        if (!isInPictureInPicture) {
            var controlsVisible by remember { mutableStateOf(true) }
            var pulse by remember { mutableStateOf<SeekPulse?>(null) }
            var pulseKey by remember { mutableIntStateOf(0) }
            // The last swipe, kept after it ends so the strip has something to fade out.
            var swipe by remember { mutableStateOf<SwipeLevel?>(null) }
            var swipeVisible by remember { mutableStateOf(false) }
            var swipeEnded by remember { mutableIntStateOf(0) }
            val hardware = rememberPlayerHardware()
            val failed = state.errorMessage != null
            // Whether the failure screen's «Удалить загрузку» has been confirmed yet.
            var confirmingRemoval by remember(state.episode) { mutableStateOf(false) }
            // What the surface over the video says, decided outside the composition: a downloaded
            // episode failing with a network is a different message with a different way out.
            val failure = remember(state.errorMessage, state.offline, state.failedReadingDownload, state.episodeUnavailable) {
                playerFailure(state)
            }
            val snackbar = remember { SnackbarHostState() }
            // Taken once per episode: the only thing measured against it is which day the next one airs.
            val now = remember(state.episode) { Instant.now() }

            // Controls linger for three seconds of uninterrupted playback. Anything that asks for a
            // decision — a failure, a countdown, an open sheet — keeps them up.
            LaunchedEffect(controlsVisible, state.isPlaying, failed, state.sheet, state.autoplayCountdownSec, state.completedPrompt) {
                val asking = state.sheet != null || state.autoplayCountdownSec != null || state.completedPrompt
                if (controlsVisible && state.isPlaying && !failed && !asking) {
                    delay(CONTROLS_LINGER_MS)
                    controlsVisible = false
                }
            }
            LaunchedEffect(failed) { if (failed) controlsVisible = true }
            LaunchedEffect(pulseKey) {
                if (pulse != null) {
                    delay(PULSE_MS)
                    pulse = null
                }
            }
            LaunchedEffect(swipeEnded) {
                if (swipeEnded == 0) return@LaunchedEffect
                delay(SWIPE_LINGER_MS)
                swipeVisible = false
            }
            LaunchedEffect(state.toast) {
                val message = state.toast ?: return@LaunchedEffect
                snackbar.showSnackbar(message)
                onToastShown()
            }
            // A session is one per process and stays where it ended, so a player opened after one
            // finished has a receipt to clear that belongs to an episode nobody here was watching.
            LaunchedEffect(Unit) { together.onPlayerAttached() }
            // A refused microphone and a link that would not open are said in the same place as
            // everything else the player says in passing.
            LaunchedEffect(together.state.message) {
                val message = together.state.message ?: return@LaunchedEffect
                snackbar.showSnackbar(message)
                together.onMessageShown()
            }
            // The invitation leaves the app the moment there is one to send.
            val context = LocalContext.current
            LaunchedEffect(together.state.share) {
                val request = together.state.share ?: return@LaunchedEffect
                // Said first: the chooser sends this task to the background, and a window folded
                // over the messenger the host has just picked is not what they asked for.
                together.onSystemPrompt(true)
                shareInvitation(context, request)
                together.onShareShown()
            }
            // The ending of the last episode stepped aside by itself and nothing follows it. The
            // card of the show is where a viewer who has just run out of it belongs, and
            // «К списку серий» is already the way there.
            LaunchedEffect(state.leaving) { if (state.leaving) onBackToEpisodes() }
            // Text that disappears on a timer is exactly what WCAG 2.2.1 will not have, so with a
            // screen reader running the corner keeps what it is given until it is dismissed.
            val talkback = touchExploration()
            LaunchedEffect(talkback) { together.onAutoHide(!talkback) }

            if (state.isCasting) {
                // Nothing is decoded here while a receiver has the picture, so there is no surface
                // to attach and nothing worth hiding after three seconds: the screen is a remote.
                //
                // A shared viewing carries on underneath it — the friend's play, pause and episode
                // changes are still applied, now to the television — so the chip that leaves one
                // and the corner that carries what is being said both come along.
                Box(Modifier.fillMaxSize()) {
                    RemoteControlScreen(
                        state = state,
                        onBack = onBack,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekTo = onSeekTo,
                        onSeekBy = onSeekBy,
                        onSkip = onSkip,
                        onNext = onNext,
                        onCancelAutoplay = onCancelAutoplay,
                        onOpenTranslations = onOpenTranslations,
                        onOpenQualities = onOpenQualities,
                        onPickEpisode = onPickEpisode,
                        onRetry = onRetry,
                        onStopCasting = onStopCasting,
                        togetherPeer = TogetherCopy.sessionChip(
                            together.state.phase,
                            together.state.peerName,
                        ),
                        onLeaveTogether = together.onLeave,
                    )
                    if (together.state.active && !failed) {
                        TogetherOverlay(
                            state = together.state,
                            controlsVisible = true,
                            onSendChat = together.onSendChat,
                            onReaction = together.onReaction,
                            onVoice = together.onVoice,
                            onMicDenied = together.onMicDenied,
                            onOpenHistory = together.onOpenHistory,
                            onCloseHistory = together.onCloseHistory,
                            onReplay = together.onReplay,
                            onLeaveWait = together.onLeaveWait,
                            onSystemPrompt = together.onSystemPrompt,
                            recorder = together.recorder,
                        )
                    }
                }
            } else {
                Box(
                    Modifier.fillMaxSize().playerGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onSeek = { forward ->
                            onSeekBy(if (forward) EpisodeQueue.SEEK_STEP_MS else -EpisodeQueue.SEEK_STEP_MS)
                            pulse = SeekPulse(forward = forward)
                            pulseKey += 1
                        },
                        onSwipeStart = { side ->
                            when (side) {
                                PlayerSide.LEFT -> hardware.brightness()
                                PlayerSide.RIGHT -> hardware.volume()
                            }
                        },
                        onSwipe = { side, level ->
                            when (side) {
                                PlayerSide.LEFT -> hardware.setBrightness(level)
                                PlayerSide.RIGHT -> hardware.setVolume(level)
                            }
                            swipe = SwipeLevel(side, level)
                            swipeVisible = true
                        },
                        onSwipeEnd = { swipeEnded += 1 },
                    ),
                )

                pulse?.let { SeekPulseBadge(it) }

                swipe?.let { level ->
                    AnimatedVisibility(
                        visible = swipeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(if (level.side == PlayerSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                            .safeDrawingPadding()
                            .padding(horizontal = 24.dp),
                    ) {
                        SwipeIndicator(level.side, level.level)
                    }
                }

                failure?.let {
                    PlaybackFailure(
                        failure = it,
                        onRetry = onRetry,
                        onChangeTranslation = onOpenTranslations,
                        // Asked for first, exactly as the top bar asks: this is the same deletion,
                        // and an error screen is the worst place to make one a single tap away.
                        onRemoveDownload = { confirmingRemoval = true },
                        onBackToEpisodes = onBackToEpisodes,
                    )
                }

                if (confirmingRemoval) {
                    RemoveDownloadSheet(
                        bytes = state.download?.bytes ?: 0,
                        onRemove = {
                            confirmingRemoval = false
                            onRemoveBrokenDownload()
                        },
                        onDismiss = { confirmingRemoval = false },
                    )
                }

                AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxWidth().height(140.dp)
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))),
                        )
                        if (!failed) {
                            Box(
                                Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
                            )
                        }
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            PlayerTopBar(
                                title = state.title,
                                episode = state.episode,
                                translationTitle = state.translationTitle,
                                qualityLabel = state.quality?.let { "${it.height}p" },
                                onBack = onBack,
                                onTranslations = onOpenTranslations,
                                onQualities = onOpenQualities,
                                // Only where there is a picture on this device to put in a window.
                                onEnterPictureInPicture = onEnterPictureInPicture?.takeIf { !state.isCasting },
                                download = state.download,
                                // Nothing to download while the picture is on a television: the
                                // episode would be kept on a phone that is not playing it.
                                onDownload = onDownload.takeIf { !state.isCasting },
                                onRemoveDownload = onRemoveDownload.takeIf { !state.isCasting },
                                onWatchTogether = together.onShare.takeIf { together.enabled },
                                togetherPeer = TogetherCopy.sessionChip(together.state.phase, together.state.peerName),
                                onLeaveTogether = together.onLeave,
                                // Nothing to start while the picture is on a television: the two
                                // phones would be watching one room from opposite ends of it. A
                                // session already running keeps its chip, which is its only exit.
                                canInvite = !state.isCasting,
                            )
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (!failed) {
                                    PlayerCenterControl(
                                        isBuffering = state.isBuffering,
                                        isPlaying = state.isPlaying,
                                        onToggle = onTogglePlayPause,
                                    )
                                }
                            }
                            if (!failed) {
                                PlayerBottomBar(
                                    positionMs = state.positionMs,
                                    bufferedPositionMs = state.bufferedPositionMs,
                                    durationMs = state.durationMs,
                                    // While the card is counting down it carries the same action; two
                                    // buttons for one decision is one button too many.
                                    // Only where there is something to move on to, and not while
                                    // the card below is already offering the same move.
                                    showNext = state.nextEpisodeAvailable && state.autoplayCountdownSec == null,
                                    onSeekTo = onSeekTo,
                                    onSeekBy = onSeekBy,
                                    onSkipIntro = onSkipIntro,
                                    onNext = onNext,
                                )
                            }
                        }
                    }
                }

                // Over the controls rather than under them: what somebody just said has to be
                // readable while the timeline is up, and the column moves itself out of the way.
                if (together.state.active && !failed) {
                    TogetherOverlay(
                        state = together.state,
                        controlsVisible = controlsVisible,
                        onSendChat = together.onSendChat,
                        onReaction = together.onReaction,
                        onVoice = together.onVoice,
                        onMicDenied = together.onMicDenied,
                        onOpenHistory = together.onOpenHistory,
                        onCloseHistory = together.onCloseHistory,
                        onReplay = together.onReplay,
                        onLeaveWait = together.onLeaveWait,
                        onSystemPrompt = together.onSystemPrompt,
                        recorder = together.recorder,
                    )
                }

                // Buffering has to be visible even after the controls have gone.
                if (!controlsVisible && state.isBuffering && !failed) {
                    PlayerCenterControl(isBuffering = true, isPlaying = false, onToggle = {}, modifier = Modifier.align(Alignment.Center))
                }
            }

            // Both live in the same corner and answer the same question: what happens when this
            // episode runs out. The remote control carries its own, in the row the decision belongs to.
            val endOfEpisode = Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                .padding(end = 24.dp, bottom = if (controlsVisible) 148.dp else 24.dp)
            when {
                state.isCasting || failed -> Unit
                // First, and that is what keeps this corner to one offer. An opening is offered
                // minutes before anything here has an opinion about the end of the episode, and
                // the ending's own button is already held back while the countdown is up.
                state.skip != null -> SkipButton(skipLabel(state.skip), onSkip, endOfEpisode)
                state.autoplayCountdownSec != null && state.nextEpisodeAvailable -> NextEpisodeCard(
                    episode = state.episode + 1,
                    countdownSec = state.autoplayCountdownSec,
                    onNow = onNext,
                    onCancel = onCancelAutoplay,
                    modifier = endOfEpisode,
                )
                // Nothing is said about a show this device has no catalogue entry for: with no aired
                // count there is no way to tell «that was the last one» from «we simply do not know».
                // Nor about a finished one, where the end of the last episode is the end of the story
                // and «Перевести в завершённые?» is already asking the only question worth asking.
                state.episodeEnding && !state.nextEpisodeAvailable &&
                    state.availableEpisodes > 0 && state.moreEpisodesComing -> LastEpisodeCard(
                    waiting = waitingLabel(
                        episode = state.episode + 1,
                        nextEpisodeAt = state.nextEpisodeAt,
                        aired = state.availableEpisodes,
                        now = now,
                    ),
                    modifier = endOfEpisode,
                )
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp))
        }
    }

    // Sheets and dialogs are windows of their own; a floating player is no place to open one.
    if (isInPictureInPicture) return

    when (state.sheet) {
        // The same sheet the title screen opens: one question, one answer, one look.
        PlayerSheet.TRANSLATIONS -> TranslationPickerSheet(
            translations = state.translations,
            currentId = state.translationId,
            onPick = onPickTranslation,
            onDismiss = onCloseSheet,
            episode = state.episode.takeIf { it > 0 },
        )
        PlayerSheet.QUALITY -> QualitySheet(
            qualities = state.qualities,
            current = state.quality,
            remembered = state.rememberQuality,
            onRemember = onRememberQuality,
            onPick = onPickQuality,
            onDismiss = onCloseSheet,
        )
        null -> Unit
    }

    if (state.completedPrompt) {
        AlertDialog(
            onDismissRequest = onDismissCompleted,
            title = { Text("Перевести «${state.title}» в завершённые?") },
            text = { Text("Серия была последней из вышедших.") },
            confirmButton = { TextButton(onClick = onConfirmCompleted) { Text("Да") } },
            dismissButton = { TextButton(onClick = onDismissCompleted) { Text("Позже") } },
        )
    }
}

private data class SeekPulse(val forward: Boolean)

private data class SwipeLevel(val side: PlayerSide, val level: Float)

@Composable
private fun SeekPulseBadge(pulse: SeekPulse) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (pulse.forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.padding(horizontal = 48.dp).size(96.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pulse.forward) "+10 с" else "−10 с",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * The video would not play. Over the frame rather than instead of it, so the top bar is still
 * reachable: from here the ways forward are trying the same stream again and one other thing.
 *
 * Which other thing depends on what failed. Normally it is another voice, which lives in the
 * chooser behind this. For an episode that is on the device and will not play with a network
 * present, the copy on the device is what failed, so the way past it is to take that copy away —
 * which also starts the episode again from the source, since one without the other leaves the
 * viewer on the same still frame wondering whether anything happened. For an episode no voice
 * has yet there is nothing to choose, so the way out is the season list.
 */
@Composable
private fun PlaybackFailure(
    failure: PlayerFailure,
    onRetry: () -> Unit,
    onChangeTranslation: () -> Unit,
    onRemoveDownload: () -> Unit,
    onBackToEpisodes: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        ErrorState(
            message = failure.message,
            onRetry = onRetry,
            secondaryLabel = when (failure.recovery) {
                PlayerRecovery.CHANGE_TRANSLATION -> "Сменить озвучку"
                PlayerRecovery.REMOVE_DOWNLOAD -> "Удалить загрузку"
                PlayerRecovery.BACK_TO_EPISODES -> "К списку серий"
            },
            onSecondary = when (failure.recovery) {
                PlayerRecovery.CHANGE_TRANSLATION -> onChangeTranslation
                PlayerRecovery.REMOVE_DOWNLOAD -> onRemoveDownload
                PlayerRecovery.BACK_TO_EPISODES -> onBackToEpisodes
            },
        )
    }
}
