package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruText

/** What the control at the end of a row header says, and what it does. */
data class RowAction(val label: String, val onClick: () -> Unit)

/**
 * The name of a row of cards, with an optional way out of it — "Всё", "Настроить".
 *
 * There is no eyebrow above it and no rule under it: the row of artwork below is what separates
 * one section from the next, and a line would only repeat that.
 */
@Composable
fun RowHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: RowAction? = null,
    gutter: Dp = KaeruTokens.GutterPhone,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = gutter, end = if (action == null) gutter else KaeruTokens.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = KaeruText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(
                onClick = action.onClick,
                modifier = Modifier.defaultMinSize(minHeight = KaeruTokens.MinTouchTarget),
                contentPadding = PaddingValues(horizontal = KaeruTokens.Space3),
            ) {
                Text(action.label, style = MaterialTheme.typography.titleMedium, color = KaeruAccent, maxLines = 1)
            }
        }
    }
}
