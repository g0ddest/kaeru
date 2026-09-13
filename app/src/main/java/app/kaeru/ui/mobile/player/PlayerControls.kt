package app.kaeru.ui.mobile.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.kaeru.ui.common.player.CastButton
import app.kaeru.ui.common.design.KaeruSeekBar
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.player.EpisodeQueue
import kotlin.math.roundToLong

/** Controls sit on video, so their own colours are alpha over whatever frame is underneath. */
private val OnVideo = Color.White
private val OnVideoMuted = Color(0xFFD6D9DE)
private val Disc = Color.Black.copy(alpha = 0.32f)

@Composable
fun PlayerTopBar(
    title: String,
    episode: Int,
    translationTitle: String?,
    qualityLabel: String?,
    onBack: () -> Unit,
    onTranslations: () -> Unit,
    onQualities: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DiscButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = OnVideo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                episode.takeIf { it > 0 }?.let { "$it серия" },
                translationTitle,
            ).joinToString("   ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnVideoMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CastButton(Modifier.padding(end = 4.dp))
        // The chips carry the current choice, so the viewer can read their settings without opening anything.
        translationTitle?.let { Chip(text = it, onClick = onTranslations) }
        qualityLabel?.let {
            Spacer(Modifier.width(8.dp))
            Chip(text = it, onClick = onQualities)
        }
    }
}

@Composable
fun PlayerCenterControl(isBuffering: Boolean, isPlaying: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (isBuffering) {
            CircularProgressIndicator(color = KaeruAccent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        } else {
            IconButton(onClick = onToggle, modifier = Modifier.size(72.dp).clip(CircleShape).background(Disc)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Продолжить",
                    tint = OnVideo,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    showNext: Boolean,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // While a finger is on the bar the timeline follows the finger, not the video.
        var scrubbing by remember { mutableStateOf<Float?>(null) }
        val shown = scrubbing?.roundToLong() ?: positionMs
        // The same inset the track keeps, so «0:00» stands over the start of the track and the
        // duration over its end rather than over the thumb's overhang.
        Row(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.SeekInset)) {
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = OnVideo)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = OnVideoMuted)
        }
        val durationSafe = maxOf(durationMs, 1L)
        KaeruSeekBar(
            progress = shown.coerceIn(0, maxOf(durationMs, 0)).toFloat() / durationSafe.toFloat(),
            onScrub = { fraction -> scrubbing = fraction.coerceIn(0f, 1f) * durationSafe },
            onScrubEnd = {
                scrubbing?.let { onSeekTo(it.roundToLong()) }
                scrubbing = null
            },
            enabled = durationMs > 0,
            buffered = bufferedPositionMs.coerceIn(0, maxOf(durationMs, 0)).toFloat() / durationSafe.toFloat(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscButton(Icons.Default.Replay10, "Назад на 10 секунд") { onSeekBy(-EpisodeQueue.SEEK_STEP_MS) }
            Spacer(Modifier.width(4.dp))
            DiscButton(Icons.Default.Forward10, "Вперёд на 10 секунд") { onSeekBy(EpisodeQueue.SEEK_STEP_MS) }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSkipIntro) { Text("+85 с", color = OnVideo) }
            Spacer(Modifier.weight(1f))
            if (showNext) {
                TextButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, tint = OnVideo)
                    Spacer(Modifier.width(6.dp))
                    Text("Следующая серия", color = OnVideo)
                }
            }
        }
    }
}

/**
 * The offer to move on. The bar under the line drains as the countdown does, so the time left
 * to say no is visible rather than only counted.
 */
@Composable
fun NextEpisodeCard(episode: Int, countdownSec: Int, onNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val drain by animateFloatAsState(
        targetValue = countdownSec.toFloat() / EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC,
        label = "autoplay",
    )
    Column(
        modifier
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KaeruElevated.copy(alpha = 0.94f))
            .padding(16.dp),
    ) {
        Text("Следующая серия через $countdownSec", style = MaterialTheme.typography.titleMedium, color = OnVideo)
        Text("$episode серия", style = MaterialTheme.typography.bodySmall, color = OnVideoMuted, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxWidth(drain.coerceIn(0f, 1f)).height(3.dp).background(KaeruAccent))
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onNow,
                colors = ButtonDefaults.buttonColors(containerColor = KaeruAccent, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("Смотреть сейчас") }
            TextButton(onClick = onCancel) { Text("Отмена", color = OnVideoMuted) }
        }
    }
}

/**
 * The other end of an episode: nothing follows it yet.
 *
 * Same place and same shape as [NextEpisodeCard], because it answers the same question at the
 * same moment — what happens when this runs out. It simply has no action to offer, so it has no
 * buttons and no drain, and says when to come back instead.
 */
@Composable
fun LastEpisodeCard(waiting: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KaeruElevated.copy(alpha = 0.94f))
            .padding(16.dp),
    ) {
        Text(waiting, style = MaterialTheme.typography.titleMedium, color = OnVideo)
        Text(
            "Пока это последняя вышедшая серия",
            style = MaterialTheme.typography.bodySmall,
            color = OnVideoMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Disc),
    ) {
        Text(text, color = OnVideo, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun DiscButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Disc)) {
        Icon(icon, contentDescription = description, tint = OnVideo)
    }
}
