package app.kaeru.ui.mobile.together

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PosterImage
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.together.JoinUiState
import app.kaeru.ui.common.together.TogetherCopy

private val PosterWidth = 168.dp

/**
 * What a link in a messenger opens.
 *
 * Portrait, because a phone in a chat is held upright, and the turn into landscape happens on the
 * way into the player rather than here. One column, centred, and read top to bottom: the artwork
 * says which show without being read, the sentence under it says who, which episode and from which
 * minute, and the button says the only thing there is to do.
 *
 * Until the other phone answers there is a room id and nothing else, so the poster and the
 * sentence are skeletons rather than plausible-looking defaults. The button waits with them: there
 * is no agreeing to something that has not said what it is yet.
 *
 * Two ways out, and both are always on screen. «Не сейчас» is the ordinary one. The other is
 * whatever the failure left possible, which is why the error line and its button replace the
 * invitation rather than sitting under it.
 */
@Composable
fun JoinScreen(
    state: JoinUiState,
    onJoin: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KaeruBackground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KaeruTokens.Space6, vertical = KaeruTokens.Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Poster(state)
        Box(Modifier.height(KaeruTokens.Space6))
        when {
            state.error != null -> Failed(state.error, state.retryable, onRetry, onDismiss)
            state.loading || state.line == null -> Waiting(state)
            else -> Invitation(state, onJoin, onDismiss)
        }
    }
}

@Composable
private fun Poster(state: JoinUiState) {
    val shape = Modifier.width(PosterWidth).aspectRatio(KaeruTokens.PosterAspect)
    if (state.title == null) {
        SkeletonGroup { Skeleton(shape) }
    } else {
        PosterImage(url = state.posterUrl, title = state.title, modifier = shape)
    }
}

/** The room answered: who is watching what, and the one thing to do about it. */
@Composable
private fun Invitation(state: JoinUiState, onJoin: () -> Unit, onDismiss: () -> Unit) {
    Text(
        "${TogetherCopy.name(state.peerName)} зовёт смотреть вместе",
        style = MaterialTheme.typography.headlineMedium,
        color = KaeruText,
        textAlign = TextAlign.Center,
    )
    Text(
        state.line.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = KaeruTokens.Space3),
    )
    PrimaryButton(
        TogetherCopy.JOIN,
        onJoin,
        Modifier.fillMaxWidth().padding(top = KaeruTokens.Space8),
    )
    TextAction(TogetherCopy.NOT_NOW, onDismiss, Modifier.padding(top = KaeruTokens.Space2))
    // Said here rather than at the first press of the microphone: this is the moment a person
    // decides whether to be in a conversation at all, so it is the moment the answer matters.
    Text(
        TogetherCopy.MIC_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = KaeruSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = KaeruTokens.Space6),
    )
}

/** The room has not answered yet. Nothing is invented to fill the gap. */
@Composable
private fun Waiting(state: JoinUiState) {
    if (state.loading) {
        Text(
            TogetherCopy.CONNECTING,
            style = MaterialTheme.typography.headlineMedium,
            color = KaeruText,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            "${TogetherCopy.name(state.peerName)} зовёт смотреть вместе",
            style = MaterialTheme.typography.headlineMedium,
            color = KaeruText,
            textAlign = TextAlign.Center,
        )
    }
    SkeletonGroup {
        Column(Modifier.padding(top = KaeruTokens.Space4), horizontalAlignment = Alignment.CenterHorizontally) {
            Skeleton(Modifier.width(220.dp).height(16.dp), KaeruTokens.ChipShape)
            Skeleton(Modifier.padding(top = KaeruTokens.Space2).width(140.dp).height(16.dp), KaeruTokens.ChipShape)
        }
    }
}

/**
 * It did not work, and what is said is what happened.
 *
 * [retryable] is the difference between a room that did not answer and a link that was never a
 * room. The first is worth another knock; the second has nothing to knock on, and a «Повторить»
 * that cannot do anything is worse than no button at all — so that case gets one way out and it
 * closes the screen.
 */
@Composable
private fun Failed(message: String, retryable: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Text(
        message,
        style = MaterialTheme.typography.headlineMedium,
        color = KaeruError,
        textAlign = TextAlign.Center,
    )
    if (retryable) {
        PrimaryButton(
            TogetherCopy.RETRY,
            onRetry,
            Modifier.fillMaxWidth().padding(top = KaeruTokens.Space8),
        )
        TextAction(TogetherCopy.WATCH_ALONE, onDismiss, Modifier.padding(top = KaeruTokens.Space2))
    } else {
        PrimaryButton(
            TogetherCopy.CLOSE,
            onDismiss,
            Modifier.fillMaxWidth().padding(top = KaeruTokens.Space8),
        )
    }
}

// --- previews -------------------------------------------------------------------------------------

@Preview(name = "Приглашение", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 720)
@Composable
private fun JoinLoadedPreview() = KaeruTheme {
    JoinScreen(
        state = JoinUiState(
            loading = false,
            peerName = "Вася",
            title = "Проводы в последний путь",
            posterUrl = null,
            animeId = 42,
            episode = 7,
            positionMs = 724_000,
            line = TogetherCopy.joinLine("Вася", "Проводы в последний путь", 7, 724_000),
        ),
        onJoin = {},
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Подключаемся", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 720)
@Composable
private fun JoinLoadingPreview() = KaeruTheme {
    JoinScreen(state = JoinUiState(), onJoin = {}, onRetry = {}, onDismiss = {})
}

@Preview(name = "Не удалось", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 720)
@Composable
private fun JoinFailedPreview() = KaeruTheme {
    JoinScreen(
        state = JoinUiState(loading = false, error = TogetherCopy.UNREACHABLE),
        onJoin = {},
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Ссылка не подходит", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 720)
@Composable
private fun JoinBadLinkPreview() = KaeruTheme {
    JoinScreen(
        state = JoinUiState(loading = false, error = TogetherCopy.BAD_LINK, retryable = false),
        onJoin = {},
        onRetry = {},
        onDismiss = {},
    )
}
