package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.CastButton
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import kotlin.math.roundToLong

/**
 * The phone while a Chromecast has the picture: a remote control, not a player.
 *
 * There is no surface to attach and nothing to hide the controls for, so everything is on
 * screen at once and nothing fades. The poster stands in for the video, which is the one thing
 * the viewer can no longer see from here.
 *
 * Volume is deliberately absent: the phone's own volume keys drive the receiver while a
 * session is up, so a slider here would be a second, worse way to do what the buttons under
 * the viewer's thumb already do.
 */
@Composable
fun RemoteControlScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onOpenTranslations: () -> Unit,
    onOpenQualities: () -> Unit,
    onRetry: () -> Unit,
    onStopCasting: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(KaeruBackground).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = KaeruText)
            }
            Text(
                // Quoted, because a device name is a name and «на Гостиная ТВ» declines badly.
                state.receiverName?.let { "Идёт трансляция на „$it“" } ?: "Идёт трансляция",
                style = MaterialTheme.typography.labelLarge,
                color = KaeruAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            CastButton()
            TextButton(onClick = onStopCasting) { Text("Отключить", color = KaeruText) }
        }

        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            Poster(state.posterUrl, state.title, Modifier.width(POSTER_WIDTH).height(POSTER_HEIGHT))
            Column(Modifier.weight(1f).padding(start = 24.dp)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = KaeruText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.episode > 0) {
                    Text(
                        "${state.episode} серия",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KaeruSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.translationTitle?.let { RemoteChip(it, onOpenTranslations) }
                    state.quality?.let { RemoteChip("${it.height}p", onOpenQualities) }
                }
                state.errorMessage?.let { message ->
                    // Inline rather than a full screen of its own: from here the two things
                    // worth doing are trying again and watching on the phone instead, and both
                    // need the rest of this screen to stay reachable.
                    Row(
                        Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) { Text("Повторить", color = KaeruAccent) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Timeline(state, onSeekTo)
                Transport(state, onTogglePlayPause, onSeekBy, onNext, onCancelAutoplay)
            }
        }
    }
}

@Composable
private fun Timeline(state: PlayerUiState, onSeekTo: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.roundToLong() ?: state.positionMs
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = KaeruText)
        Spacer(Modifier.weight(1f))
        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
    }
    Slider(
        value = shown.coerceIn(0, maxOf(state.durationMs, 0)).toFloat(),
        onValueChange = { scrubbing = it },
        onValueChangeFinished = {
            scrubbing?.let { onSeekTo(it.roundToLong()) }
            scrubbing = null
        },
        valueRange = 0f..maxOf(state.durationMs, 1L).toFloat(),
        enabled = state.durationMs > 0,
        colors = SliderDefaults.colors(
            thumbColor = KaeruAccent,
            activeTrackColor = KaeruAccent,
            inactiveTrackColor = Color.White.copy(alpha = 0.24f),
            disabledThumbColor = KaeruSecondary,
            disabledActiveTrackColor = KaeruSecondary,
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.16f),
        ),
    )
}

@Composable
private fun Transport(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteButton(Icons.Default.Replay10, "Назад на 10 секунд") { onSeekBy(-EpisodeQueue.SEEK_STEP_MS) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (state.isBuffering) {
                CircularProgressIndicator(color = KaeruAccent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            } else {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(KaeruElevated),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Продолжить",
                        tint = KaeruText,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        RemoteButton(Icons.Default.Forward10, "Вперёд на 10 секунд") { onSeekBy(EpisodeQueue.SEEK_STEP_MS) }
        Spacer(Modifier.weight(1f))
        // The countdown takes over the button rather than floating over it: this screen has
        // nothing to float above, and one decision deserves one place to make it.
        val countdown = state.autoplayCountdownSec
        if (countdown == null) {
            TextButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = null, tint = KaeruText)
                Spacer(Modifier.width(6.dp))
                Text("Следующая серия", color = KaeruText)
            }
        } else {
            TextButton(onClick = onNext) { Text("Следующая серия через $countdown", color = KaeruAccent) }
            TextButton(onClick = onCancelAutoplay) { Text("Отмена", color = KaeruSecondary) }
        }
    }
}

@Composable
private fun RemoteChip(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(KaeruElevated),
    ) {
        Text(text, color = KaeruText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(KaeruElevated)) {
        Icon(icon, contentDescription = description, tint = KaeruText)
    }
}

private val POSTER_WIDTH = 150.dp
private val POSTER_HEIGHT = 225.dp
