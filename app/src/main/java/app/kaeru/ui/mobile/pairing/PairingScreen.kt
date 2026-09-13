package app.kaeru.ui.mobile.pairing

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.pairing.PairingStage
import app.kaeru.ui.common.pairing.PairingUiState
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private const val TITLE = "Вход на телевизоре"
private const val BACK = "Отмена"
private const val CONFIRM = "Войти"
private const val CANCEL = "Отмена"
private const val FINISH = "Готово"
private const val CLOSE = "Закрыть"

private const val UNNAMED_TV = "Войти на этом телевизоре?"
private const val EXPLAIN =
    "Kaeru откроет Shikimori за одноразовым кодом. Код уйдёт только на этот телевизор."

private const val WAITING_TITLE = "Ждём ответа Shikimori"
private const val WAITING_TEXT = "Подтвердите вход на открывшейся странице и вернитесь сюда."

private const val SENDING_TITLE = "Передаём код телевизору"
private const val SENDING_TEXT = "Телевизор проверяет код у Shikimori. Это занимает пару секунд."

private const val DONE_TITLE = "Телевизор вошёл в аккаунт"
private const val DONE_TEXT = "Список и просмотренные серии уже там."

private const val BAD_LINK_TITLE = "Ссылка не подошла"

/** A paragraph at this size stays under the length an eye can track back from. */
private val TextColumn = 320.dp

/**
 * The one question a phone asks on behalf of a television: is this yours?
 *
 * Everything on this screen is arranged around that question being answered in a second, standing
 * up, with the television in view. There is one amber control at a time and it always says what
 * pressing it does. The address the code will travel to is deliberately not shown: it is a number
 * nobody can verify by looking at it, and the name on the television's own screen is the thing a
 * person can actually check.
 *
 * [onConfirm] returns the page to open in the browser, arming a fresh authorization as it goes —
 * so it is called once per attempt, exactly like the phone's own sign-in.
 */
@Composable
fun PairingScreen(state: PairingUiState, onConfirm: () -> String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val authorize: () -> Unit = {
        onConfirm()?.let { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
    }
    BackHandler(enabled = state.stage != PairingStage.SENDING, onBack = onDismiss)
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(
            title = TITLE,
            navigationIcon = {
                if (state.stage != PairingStage.SENDING) {
                    IconAction(Icons.AutoMirrored.Filled.ArrowBack, BACK, onDismiss)
                }
            },
        )
        when {
            state.stage == PairingStage.FAILED -> EmptyState(
                title = BAD_LINK_TITLE,
                text = state.errorMessage.orEmpty(),
                modifier = Modifier.fillMaxSize(),
                actionLabel = CLOSE,
                onAction = onDismiss,
            )
            state.errorMessage != null -> ErrorState(
                message = state.errorMessage,
                onRetry = authorize,
                modifier = Modifier.fillMaxSize(),
                secondaryLabel = CANCEL,
                onSecondary = onDismiss,
            )
            else -> Question(state, authorize, onDismiss)
        }
    }
}

/**
 * The question, and then what became of it. One block of text in the same place throughout, so the
 * screen answers rather than rebuilds itself: only the heading, the sentence under it and the
 * control at the bottom change as the code travels.
 */
@Composable
private fun Question(state: PairingUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = KaeruTokens.GutterPhone)
            .padding(bottom = KaeruTokens.Space8),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            heading(state),
            style = MaterialTheme.typography.headlineMedium,
            color = KaeruText,
            modifier = Modifier.widthIn(max = TextColumn),
        )
        Text(
            body(state),
            style = MaterialTheme.typography.bodyMedium,
            color = KaeruSecondary,
            modifier = Modifier.padding(top = KaeruTokens.Space3).widthIn(max = TextColumn),
        )
        // A bar that breathes rather than a spinner: the wait here is the television's own round
        // trip to Shikimori, which is long enough that a spinner would read as a stall.
        if (state.stage == PairingStage.SENDING) {
            Skeleton(
                Modifier
                    .padding(top = KaeruTokens.Space6)
                    .widthIn(max = TextColumn)
                    .fillMaxWidth()
                    .height(KaeruTokens.ProgressHeight),
                shape = CircleShape,
            )
        }
        when (state.stage) {
            PairingStage.CONFIRM -> {
                PrimaryButton(
                    CONFIRM,
                    onConfirm,
                    modifier = Modifier.padding(top = KaeruTokens.Space8).widthIn(max = TextColumn).fillMaxWidth(),
                )
                // Pulled back by the text button's own padding so its label lines up with the column.
                TextAction(CANCEL, onDismiss, Modifier.offset(x = -KaeruTokens.Space3))
            }
            PairingStage.AWAITING_CODE ->
                TextAction(CANCEL, onDismiss, Modifier.padding(top = KaeruTokens.Space6).offset(x = -KaeruTokens.Space3))
            PairingStage.DONE -> PrimaryButton(
                FINISH,
                onDismiss,
                modifier = Modifier.padding(top = KaeruTokens.Space8).widthIn(max = TextColumn).fillMaxWidth(),
            )
            else -> Unit
        }
    }
}

/**
 * The heading, which is the whole screen in one line.
 *
 * A television that gave no name of its own still gets asked about: a link with a blank name is a
 * link worth confirming, not one worth refusing, and «этом телевизоре» says exactly as much as is
 * actually known.
 */
internal fun heading(state: PairingUiState): String {
    val name = state.request?.name?.trim().orEmpty()
    return when (state.stage) {
        PairingStage.AWAITING_CODE -> WAITING_TITLE
        PairingStage.SENDING -> SENDING_TITLE
        PairingStage.DONE -> DONE_TITLE
        else -> if (name.isEmpty()) UNNAMED_TV else "Войти на телевизоре «$name»?"
    }
}

internal fun body(state: PairingUiState): String = when (state.stage) {
    PairingStage.AWAITING_CODE -> WAITING_TEXT
    PairingStage.SENDING -> SENDING_TEXT
    PairingStage.DONE -> DONE_TEXT
    else -> EXPLAIN
}
