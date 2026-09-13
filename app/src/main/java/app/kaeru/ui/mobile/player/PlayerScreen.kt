package app.kaeru.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.player.PlayerSheet
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruAccent
import kotlinx.coroutines.delay

private const val CONTROLS_LINGER_MS = 3_000L
private const val PULSE_MS = 450L

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
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onCloseSheet: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onPickQuality: (Quality) -> Unit,
    onRetry: () -> Unit,
    onStopCasting: () -> Unit,
    onConfirmCompleted: () -> Unit,
    onDismissCompleted: () -> Unit,
    onToastShown: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var pulse by remember { mutableStateOf<SeekPulse?>(null) }
    var pulseKey by remember { mutableIntStateOf(0) }
    val failed = state.errorMessage != null
    val snackbar = remember { SnackbarHostState() }

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
    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onToastShown()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.isCasting) {
            // Nothing is decoded here while a receiver has the picture, so there is no surface
            // to attach and nothing worth hiding after three seconds: the screen is a remote.
            RemoteControlScreen(
                state = state,
                onBack = onBack,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBy = onSeekBy,
                onNext = onNext,
                onCancelAutoplay = onCancelAutoplay,
                onOpenTranslations = onOpenTranslations,
                onOpenQualities = onOpenQualities,
                onRetry = onRetry,
                onStopCasting = onStopCasting,
            )
        } else {
            if (player != null) ContentFrame(player, Modifier.fillMaxSize())

            Box(
                Modifier.fillMaxSize().pointerInput(state.durationMs) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> {
                                    onSeekBy(-EpisodeQueue.SEEK_STEP_MS)
                                    pulse = SeekPulse(forward = false)
                                    pulseKey += 1
                                }
                                offset.x > size.width - third -> {
                                    onSeekBy(EpisodeQueue.SEEK_STEP_MS)
                                    pulse = SeekPulse(forward = true)
                                    pulseKey += 1
                                }
                                else -> controlsVisible = !controlsVisible
                            }
                        },
                    )
                },
            )

            pulse?.let { SeekPulseBadge(it) }

            if (failed) {
                PlaybackFailure(
                    message = state.errorMessage.orEmpty(),
                    onRetry = onRetry,
                    onChangeTranslation = onOpenTranslations,
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
                                showNext = state.autoplayCountdownSec == null,
                                onSeekTo = onSeekTo,
                                onSeekBy = onSeekBy,
                                onSkipIntro = onSkipIntro,
                                onNext = onNext,
                            )
                        }
                    }
                }
            }

            // Buffering has to be visible even after the controls have gone.
            if (!controlsVisible && state.isBuffering && !failed) {
                PlayerCenterControl(isBuffering = true, isPlaying = false, onToggle = {}, modifier = Modifier.align(Alignment.Center))
            }
        }

        // The remote control carries its own countdown, in the row the decision belongs to.
        state.autoplayCountdownSec?.takeIf { !state.isCasting }?.let { seconds ->
            NextEpisodeCard(
                episode = state.episode + 1,
                countdownSec = seconds,
                onNow = onNext,
                onCancel = onCancelAutoplay,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                    .padding(end = 24.dp, bottom = if (controlsVisible) 148.dp else 24.dp),
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp))
    }

    when (state.sheet) {
        PlayerSheet.TRANSLATIONS -> TranslationSheet(
            translations = state.translations,
            currentId = state.translationId,
            onPick = onPickTranslation,
            onDismiss = onCloseSheet,
        )
        PlayerSheet.QUALITY -> QualitySheet(
            qualities = state.qualities,
            current = state.quality,
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

@Composable
private fun PlaybackFailure(message: String, onRetry: () -> Unit, onChangeTranslation: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = KaeruAccent, contentColor = Color.Black),
                ) { Text("Повторить") }
                OutlinedButton(onClick = onChangeTranslation) { Text("Сменить озвучку", color = Color.White) }
            }
        }
    }
}
