package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.CastButton
import app.kaeru.ui.common.design.KaeruSeekBar
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruOnAccent
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
    onPickEpisode: (Int) -> Unit,
    onRetry: () -> Unit,
    onStopCasting: () -> Unit,
    /**
     * The friend on the other phone, while there is one.
     *
     * Casting changes nothing about a shared viewing: the friend's play, pause and seek are still
     * applied, now to the television. So the chip comes with it — it is the only way out of a
     * session, and a remote control without it is a session a viewer cannot leave.
     */
    togetherPeer: String? = null,
    onLeaveTogether: (() -> Unit)? = null,
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
            if (togetherPeer != null) {
                TogetherButton(togetherPeer, onShare = {}, onLeave = onLeaveTogether, canInvite = false)
            }
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

        // The one thing the phone can do that the television's own remote cannot: jump straight
        // to another episode. Across the bottom rather than beside the poster, because it is a
        // list of many and everything above it is a list of one.
        EpisodeStrip(state, onPickEpisode)
    }
}

/**
 * The season, as something to press.
 *
 * The same three facts the title screen's grid draws — counted, in progress, not aired yet — in
 * a strip that fits under a landscape remote. An episode that has not aired is drawn and not
 * offered, so the shape of the season is still readable.
 */
@Composable
private fun EpisodeStrip(state: PlayerUiState, onPick: (Int) -> Unit) {
    if (state.episodes.size < 2) return
    val listState = rememberLazyListState()
    // Opens on what is playing rather than on episode one: a viewer on episode 24 should not
    // have to scroll to find where they are.
    LaunchedEffect(state.episode, state.episodes.size) {
        val index = state.episodes.indexOfFirst { it.number == state.episode }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
        Text(
            "Серии",
            style = MaterialTheme.typography.titleMedium,
            color = KaeruText,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.episodes, key = { it.number }) { cell ->
                EpisodeTile(cell, playing = cell.number == state.episode, onPick = { onPick(cell.number) })
            }
        }
    }
}

@Composable
private fun EpisodeTile(cell: EpisodeCell, playing: Boolean, onPick: () -> Unit) {
    val label = when {
        playing -> "${cell.number} серия, идёт сейчас"
        !cell.aired -> "${cell.number} серия, ещё не вышла"
        cell.watched -> "${cell.number} серия, просмотрена"
        else -> "${cell.number} серия"
    }
    Box(
        Modifier
            .size(TILE_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) KaeruAccent else KaeruElevated)
            .clickable(enabled = cell.aired && !playing, onClickLabel = label, onClick = onPick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = when {
                playing -> KaeruOnAccent
                !cell.aired -> KaeruSecondary
                cell.watched -> KaeruSecondary
                else -> KaeruText
            },
        )
        cell.progress?.takeIf { !playing }?.let {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                ProgressStrip(it)
            }
        }
    }
}

private val TILE_SIZE = 56.dp

@Composable
private fun Timeline(state: PlayerUiState, onSeekTo: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.roundToLong() ?: state.positionMs
    val durationSafe = maxOf(state.durationMs, 1L)
    Row(Modifier.fillMaxWidth().padding(horizontal = KaeruTokens.SeekInset)) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = KaeruText)
        Spacer(Modifier.weight(1f))
        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
    }
    KaeruSeekBar(
        progress = shown.coerceIn(0, maxOf(state.durationMs, 0)).toFloat() / durationSafe.toFloat(),
        onScrub = { fraction -> scrubbing = fraction.coerceIn(0f, 1f) * durationSafe },
        onScrubEnd = {
            scrubbing?.let { onSeekTo(it.roundToLong()) }
            scrubbing = null
        },
        enabled = state.durationMs > 0,
        // A receiver buffers on its own side and tells us nothing about it, so there is nothing
        // honest to draw ahead of the position while casting.
        buffered = 0f,
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
        when {
            !state.nextEpisodeAvailable -> Unit
            countdown == null -> TextButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = null, tint = KaeruText)
                Spacer(Modifier.width(6.dp))
                Text("Следующая серия", color = KaeruText)
            }
            else -> {
                TextButton(onClick = onNext) { Text("Следующая серия через $countdown", color = KaeruAccent) }
                TextButton(onClick = onCancelAutoplay) { Text("Отмена", color = KaeruSecondary) }
            }
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
