package app.kaeru.ui.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.requestFocusOrLog

/**
 * The moments the player stops and asks: move on to the next episode, close the show off, or
 * try the one that would not play again.
 */

/** The offer to move on, with the time left to say no draining under the line. */
@Composable
fun TvAutoplayCard(
    episode: Int,
    countdownSec: Int,
    onNow: () -> Unit,
    onCancel: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { nowButton.requestFocusOrLog("кнопку «Смотреть сейчас»") }
    val drain by animateFloatAsState(
        targetValue = countdownSec.toFloat() / EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC,
        label = "tvAutoplay",
    )
    Column(
        modifier
            .width(420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KaeruBackground.copy(alpha = 0.96f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Следующая серия через $countdownSec", style = MaterialTheme.typography.titleLarge, color = OnVideo)
        Text("$episode серия", style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(drain.coerceIn(0f, 1f)).fillMaxHeight().background(KaeruAccent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvControl(
                label = "Смотреть сейчас",
                onClick = onNow,
                primary = true,
                focusRequester = nowButton,
                onFocused = onFocused,
            )
            TvControl(label = "Отмена", onClick = onCancel, onFocused = onFocused)
        }
    }
}

/** The finale is behind the viewer; the list is not going to update itself. */
@Composable
fun TvCompletedDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { yes.requestFocusOrLog("кнопку «Да»") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp))
                .background(KaeruBackground).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Перевести «$title» в завершённые?",
                style = MaterialTheme.typography.headlineSmall,
                color = OnVideo,
                textAlign = TextAlign.Center,
            )
            Text("Серия была последней из вышедших.", style = MaterialTheme.typography.titleSmall, color = OnVideoMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvControl(label = "Да", onClick = onConfirm, primary = true, focusRequester = yes)
                TvControl(label = "Позже", onClick = onDismiss)
            }
        }
    }
}

/** Nothing is playing and nothing will until the viewer chooses one of two ways forward. */
@Composable
fun TvPlaybackFailure(message: String, onRetry: () -> Unit, onChangeTranslation: () -> Unit) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { retry.requestFocusOrLog("кнопку «Повторить»") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.headlineSmall,
                color = OnVideo,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvControl(label = "Повторить", onClick = onRetry, primary = true, focusRequester = retry)
                TvControl(label = "Сменить озвучку", onClick = onChangeTranslation)
            }
        }
    }
}

/** One line, said once, that needs no answer. */
@Composable
fun TvPlayerToast(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(KaeruBackground.copy(alpha = 0.94f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleSmall, color = OnVideo)
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvAutoplayCardPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF20242E)), contentAlignment = Alignment.Center) {
            TvAutoplayCard(episode = 8, countdownSec = 6, onNow = {}, onCancel = {}, onFocused = {})
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlaybackFailurePreview() {
    KaeruTvTheme {
        TvPlaybackFailure("Источник временно недоступен", onRetry = {}, onChangeTranslation = {})
    }
}
