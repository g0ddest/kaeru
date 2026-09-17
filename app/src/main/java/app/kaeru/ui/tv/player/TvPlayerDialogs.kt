package app.kaeru.ui.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.player.EpisodeQueue
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.player.PlayerFailure
import app.kaeru.ui.common.player.PlayerRecovery
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvDialog
import app.kaeru.ui.tv.requestFocusOrLog
import kotlin.math.absoluteValue

/**
 * The moments the player stops and says something: move on to the next episode, wait for one
 * that has not aired, close the show off, or try again what would not play.
 *
 * All of them are cards over the picture rather than rungs of the panel, because all of them ask
 * a question the panel cannot hold: the panel is where the viewer chooses, and these are where
 * the app does the asking.
 */

private const val WATCH_NOW = "Смотреть сейчас"
private const val CANCEL = "Отмена"
private const val LAST_AIRED = "Пока это последняя вышедшая серия"
private const val COMPLETE_TITLE = "Перевести в завершённые?"
private const val COMPLETE_YES = "Перевести"
private const val COMPLETE_LATER = "Пока нет"
private const val RETRY_TRACK = "Сменить озвучку"
private const val BACK_TO_EPISODES = "К списку серий"
private const val REWIND_SPOKEN = "Назад"
private const val FORWARD_SPOKEN = "Вперёд"

/** Wide enough for «Следующая серия через 10» on one line at the television type scale. */
private val CardWidth = 460.dp

private val SeekMark = 28.dp

/** The offer to move on, with the time left to say no draining under the line. */
@Composable
fun TvAutoplayCard(
    episode: Int,
    countdownSec: Int,
    onNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { nowButton.requestFocusOrLog("кнопку «$WATCH_NOW»") }
    val drain by animateFloatAsState(
        targetValue = countdownSec.toFloat() / EpisodeQueue.AUTOPLAY_COUNTDOWN_SEC,
        label = "tvAutoplay",
    )
    TvPlayerCard(modifier) {
        Text(
            "Следующая серия через $countdownSec",
            style = MaterialTheme.typography.titleLarge,
            color = KaeruText,
        )
        Text("$episode серия", style = MaterialTheme.typography.titleSmall, color = KaeruSecondary)
        ProgressStrip(drain, Modifier.padding(vertical = KaeruTokens.Space2))
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            PrimaryButton(WATCH_NOW, onNow, Modifier.focusRequester(nowButton))
            SecondaryButton(CANCEL, onCancel)
        }
    }
}

/**
 * The episode ran out and there is no next one yet.
 *
 * It says what the watch button on the title screen says — «13 серия выйдет завтра», «Ждём 13
 * серию» — because they are answering the same question about the same episode, and a countdown
 * to something the source does not have would be a promise the player cannot keep.
 *
 * Nothing to press: there is no decision here, only a date.
 */
@Composable
fun TvWaitingCard(waiting: String, modifier: Modifier = Modifier) {
    TvPlayerCard(modifier) {
        Text(waiting, style = MaterialTheme.typography.titleLarge, color = KaeruText)
        Text(LAST_AIRED, style = MaterialTheme.typography.titleSmall, color = KaeruSecondary)
    }
}

/** The one shape both end-of-episode cards take, so they read as the same kind of thing. */
@Composable
private fun TvPlayerCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .width(CardWidth)
            .clip(KaeruTokens.CardShape)
            .background(KaeruSurface.copy(alpha = 0.96f))
            .padding(KaeruTokens.Space6),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        content = { content() },
    )
}

/** The finale is behind the viewer; the list is not going to update itself. */
@Composable
fun TvCompletedDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { yes.requestFocusOrLog("кнопку «$COMPLETE_YES»") }
    TvDialog(
        title = COMPLETE_TITLE,
        onDismiss = onDismiss,
        text = "«$title» — это была последняя вышедшая серия.",
    ) {
        Row(
            Modifier.padding(top = KaeruTokens.Space2),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            PrimaryButton(COMPLETE_YES, onConfirm, Modifier.focusRequester(yes))
            SecondaryButton(COMPLETE_LATER, onDismiss)
        }
    }
}

/**
 * Nothing is playing and nothing will until the viewer chooses one of two ways forward.
 *
 * The same state the rest of the app draws when something fails — the cause, and a button per way
 * out — over a scrim heavy enough to read it against a frozen frame. Which second way out is the
 * [PlayerRecovery]'s call, made once for both screens: the voices strip, because a source that
 * will not play one track very often plays another; or the title, for an episode no track has.
 * A recovery the television cannot draw — deleting a download it never made — leaves «Повторить»
 * on its own.
 *
 * The focus request lands on the group rather than on a button, so «Повторить» takes it without
 * this file reaching into the shared component for a requester it has no business holding.
 */
@Composable
fun TvPlaybackFailure(
    failure: PlayerFailure,
    onRetry: () -> Unit,
    onChangeTranslation: () -> Unit,
    onBackToEpisodes: () -> Unit,
) {
    val buttons = remember { FocusRequester() }
    LaunchedEffect(Unit) { buttons.requestFocusOrLog("кнопки экрана ошибки") }
    Box(
        Modifier.fillMaxSize().background(KaeruBackground.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        ErrorState(
            message = failure.message,
            onRetry = onRetry,
            modifier = Modifier.focusRequester(buttons).focusGroup(),
            secondaryLabel = when (failure.recovery) {
                PlayerRecovery.CHANGE_TRANSLATION -> RETRY_TRACK
                PlayerRecovery.BACK_TO_EPISODES -> BACK_TO_EPISODES
                PlayerRecovery.REMOVE_DOWNLOAD -> null
            },
            onSecondary = when (failure.recovery) {
                PlayerRecovery.CHANGE_TRANSLATION -> onChangeTranslation
                PlayerRecovery.BACK_TO_EPISODES -> onBackToEpisodes
                PlayerRecovery.REMOVE_DOWNLOAD -> null
            },
        )
    }
}

/**
 * What a jog did, over a picture the panel has left alone.
 *
 * Scrubbing on a television is the one thing done blind — the controls are down, and they stay
 * down, or the viewer would be hunting for a frame behind the very panel that hid it. So this
 * says the one thing they cannot see for themselves: which way, and by how much, since a held
 * button widens the step to half a minute and then to a whole one.
 */
@Composable
fun TvSeekIndicator(deltaMs: Long, modifier: Modifier = Modifier) {
    val forward = deltaMs >= 0
    Row(
        modifier
            .clip(KaeruTokens.ChipShape)
            .background(KaeruBackground.copy(alpha = 0.82f))
            .padding(horizontal = KaeruTokens.Space6, vertical = KaeruTokens.Space3),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = if (forward) FORWARD_SPOKEN else REWIND_SPOKEN,
            tint = KaeruText,
            modifier = Modifier.size(SeekMark),
        )
        Text(
            // Signed, in the same two words the transport buttons use: one phrase for one idea,
            // whether the viewer reads it on a button or over the picture.
            "${if (forward) "+" else "−"}${deltaMs.absoluteValue / 1000} с",
            style = MaterialTheme.typography.titleLarge,
            color = KaeruText,
        )
    }
}

/** One line, said once, that needs no answer. */
@Composable
fun TvPlayerToast(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(KaeruTokens.CardShape)
            .background(KaeruSurface.copy(alpha = 0.96f))
            .padding(horizontal = KaeruTokens.Space6, vertical = KaeruTokens.Space4),
    ) {
        Text(message, style = MaterialTheme.typography.titleSmall, color = KaeruText)
    }
}

// --- previews ----------------------------------------------------------------------------------

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvAutoplayCardPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(KaeruBackground), contentAlignment = Alignment.BottomEnd) {
            TvAutoplayCard(episode = 8, countdownSec = 6, onNow = {}, onCancel = {}, modifier = Modifier.padding(56.dp))
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvWaitingCardPreview() {
    KaeruTvTheme {
        Box(Modifier.fillMaxSize().background(KaeruBackground), contentAlignment = Alignment.BottomEnd) {
            TvWaitingCard("11 серия выйдет завтра", Modifier.padding(56.dp))
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvPlaybackFailurePreview() {
    KaeruTvTheme {
        TvPlaybackFailure(
            PlayerFailure("Нет соединения. Проверьте интернет", PlayerRecovery.CHANGE_TRANSLATION),
            onRetry = {},
            onChangeTranslation = {},
            onBackToEpisodes = {},
        )
    }
}

/** Both directions, since the sign is the thing being previewed. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSeekIndicatorPreview() {
    KaeruTvTheme {
        Column(
            Modifier.fillMaxSize().background(KaeruBackground),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space6, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvSeekIndicator(-10_000)
            TvSeekIndicator(30_000)
        }
    }
}
