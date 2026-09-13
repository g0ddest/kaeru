package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

/** Keeps a centred paragraph near 45 characters a line, which is where it stays readable. */
private val TextColumn = 320.dp

/**
 * A screen with nothing on it yet — the only place in the app where text is centred, because there
 * is no column of content for it to line up with.
 *
 * The title names what is missing and the text says how to get some; an empty screen with only a
 * shrug on it wastes the one moment the app has the viewer's whole attention.
 */
@Composable
fun EmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = KaeruTokens.Space8, vertical = KaeruTokens.Space8),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = KaeruText,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TextColumn),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = KaeruSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = KaeruTokens.Space3).widthIn(max = TextColumn),
        )
        if (actionLabel != null && onAction != null) {
            PrimaryButton(actionLabel, onAction, modifier = Modifier.padding(top = KaeruTokens.Space6))
        }
    }
}

/**
 * Something failed and the viewer can do something about it.
 *
 * [message] comes from `toUserMessage()`, which already says the cause and the next step, so
 * nothing is added here — no icon, no apology, no second heading. The retry is the amber button
 * because it is what the screen now exists to do; [secondaryLabel] is for the other way out, such
 * as changing the dub when this one will not load.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = KaeruTokens.Space8, vertical = KaeruTokens.Space8),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = KaeruText,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TextColumn),
        )
        Row(
            Modifier.padding(top = KaeruTokens.Space6),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            PrimaryButton("Повторить", onRetry)
            if (secondaryLabel != null && onSecondary != null) {
                SecondaryButton(secondaryLabel, onSecondary)
            }
        }
    }
}
