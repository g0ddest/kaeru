package app.kaeru.ui.mobile.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.ui.common.downloads.DownloadMark
import app.kaeru.ui.common.downloads.downloadMark
import app.kaeru.ui.common.player.CastButton
import app.kaeru.ui.common.together.TogetherCopy
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
    onEnterPictureInPicture: (() -> Unit)? = null,
    download: EpisodeDownload? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    togetherPeer: String? = null,
    onLeaveTogether: (() -> Unit)? = null,
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
            // The episode alone: the dub is the chip at the other end of this same row, and a
            // row that names it twice reads as two different facts about the same thing.
            val subtitle = episode.takeIf { it > 0 }?.let { "$it серия" }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnVideoMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onWatchTogether != null) {
            TogetherButton(togetherPeer, onWatchTogether, onLeaveTogether)
            Spacer(Modifier.width(4.dp))
        }
        if (onDownload != null && onRemoveDownload != null) {
            DownloadButton(download, onDownload, onRemoveDownload)
            Spacer(Modifier.width(4.dp))
        }
        onEnterPictureInPicture?.let {
            DiscButton(Icons.Default.PictureInPictureAlt, "В окно", it)
            Spacer(Modifier.width(4.dp))
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
 * What a swipe over the video is doing, while it does it.
 *
 * A strip rather than a number: the viewer is dragging a level, and a bar that fills answers
 * «how far up am I» at a glance, where «62 %» has to be read. It stands on the side the finger
 * is on, because that is the half of the picture the gesture belongs to, and it fades rather
 * than disappearing so the last value can be checked after the thumb has gone.
 */
@Composable
fun SwipeIndicator(side: PlayerSide, level: Float, modifier: Modifier = Modifier) {
    val brightness = side == PlayerSide.LEFT
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Disc)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (brightness) Icons.Default.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (brightness) "Яркость" else "Громкость",
            tint = OnVideo,
            modifier = Modifier.size(20.dp),
        )
        Box(
            Modifier.padding(top = 10.dp).width(INDICATOR_WIDTH).height(INDICATOR_HEIGHT)
                .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.24f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier.width(INDICATOR_WIDTH)
                    .height(INDICATOR_HEIGHT * level.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(OnVideo),
            )
        }
    }
}

private val INDICATOR_WIDTH = 6.dp
private val INDICATOR_HEIGHT = 120.dp

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
        modifier = Modifier
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .clip(RoundedCornerShape(10.dp))
            .background(Disc),
    ) {
        Text(text, color = OnVideo, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * Keep this episode on the device, or give the space back.
 *
 * Three things to say and one control to say them with: an arrow for «this is not here yet», the
 * share done for one on its way, and a done mark for one that is. The percentage replaces the
 * glyph rather than joining it — a disc this size has room for one or the other, and while a
 * download is running the number is the more useful of the two.
 *
 * Anything the engine is already holding removes rather than downloads, including a queue that has
 * not started: pressing «скачать» on something already being downloaded can only mean «отмени».
 * A press that would delete something asks first; a press that only starts a download does not.
 */
@Composable
private fun DownloadButton(download: EpisodeDownload?, onDownload: () -> Unit, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    // The same four readings of the engine the season grid draws, so one episode cannot be «в
    // очереди» on one screen and «скачано» on the other. Only the glyphs differ, which is what
    // the two surfaces are actually free to disagree about.
    when (downloadMark(download?.state)) {
        DownloadMark.NONE, DownloadMark.FAILED ->
            DiscButton(Icons.Default.Download, "Скачать серию", onDownload)
        DownloadMark.PENDING ->
            DiscButton(Icons.Default.Download, "Отменить загрузку", { confirming = true }, pending = true)
        DownloadMark.RUNNING -> DiscLabel(
            "${((download?.progress ?: 0f) * 100).toInt()} %",
            "Отменить загрузку",
        ) { confirming = true }
        DownloadMark.DONE -> DiscButton(Icons.Default.DownloadDone, "Удалить загрузку", { confirming = true })
    }
    if (confirming) {
        RemoveDownloadSheet(
            bytes = download?.bytes ?: 0,
            onRemove = {
                confirming = false
                onRemove()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * The invitation, and — once somebody has taken it — who took it.
 *
 * Two figures while there is nobody, the friend's name the moment there is. The name is a chip
 * rather than an icon because that is how this row already says «this is what is currently
 * chosen»: the dub and the quality are chips, and the person on the other phone is the same kind
 * of fact about the session. It opens the one thing there is to decide about a session already
 * running, which is whether to be in it.
 */
@Composable
private fun TogetherButton(peer: String?, onShare: () -> Unit, onLeave: (() -> Unit)?) {
    if (peer == null) {
        DiscButton(Icons.Default.Groups, TogetherCopy.WATCH_TOGETHER, onShare)
        return
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Chip(text = peer, onClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(TogetherCopy.LEAVE) },
                onClick = {
                    menu = false
                    onLeave?.invoke()
                },
            )
        }
    }
}

/** The same disc with a number on it, for the one state that is a quantity rather than a thing. */
@Composable
private fun DiscLabel(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Disc)
            .clickable(onClick = onClick, onClickLabel = description),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = OnVideo, maxLines = 1)
    }
}

/**
 * The same disc, drawn a step quieter for an episode that has been asked for but is not here yet.
 *
 * Muted rather than disabled: pressing it is how a queue is cancelled, so it has to stay pressable
 * — it just should not look like something already on the device.
 */
@Composable
private fun DiscButton(icon: ImageVector, description: String, onClick: () -> Unit, pending: Boolean) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Disc)) {
        Icon(icon, contentDescription = description, tint = if (pending) OnVideoMuted else OnVideo)
    }
}

@Composable
private fun DiscButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Disc)) {
        Icon(icon, contentDescription = description, tint = OnVideo)
    }
}
