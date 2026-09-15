package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private val ChevronSize = 18.dp

/**
 * What the control at the end of a row header says, and what it does.
 *
 * [icon] is the chevron by default, because most of these lead somewhere. An action that opens a
 * sheet over the screen passes null: a chevron there promises a screen that never arrives.
 */
data class RowAction(
    val label: String,
    val icon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    val onClick: () -> Unit,
)

/**
 * The name of a row of cards, with an optional way out of it — "Всё", "Настроить".
 *
 * There is no eyebrow above it and no rule under it: the row of artwork below is what separates
 * one section from the next, and a line would only repeat that.
 *
 * The action is deliberately not amber. The accent belongs to «Смотреть», to progress, to focus on
 * a television and to the active tab; a home screen with a hero and four rows would otherwise put
 * five ambers on one screen and none of them would mean anything. It reads as a control because it
 * is a step quieter than the title and carries a chevron, not because it is coloured.
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
            TextAction(action.label, action.onClick, trailingIcon = action.icon)
        }
    }
}

/**
 * The quiet control that leads somewhere or unfolds something: «Всё», «Ещё», «Показать ещё».
 *
 * Deliberately not amber. The accent belongs to «Смотреть», to progress, to focus on a television
 * and to the active tab; a screen with a hero and four of these would otherwise put five ambers on
 * one page and none of them would mean anything. It reads as a control because it is a step
 * quieter than the text it sits under and, where it leads somewhere, carries a chevron.
 *
 * It is a full 48dp tall whatever its label, so a thumb can reach it.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .kaeruFocus(KaeruTokens.ButtonShape),
        // Material's text button is a stadium, and the focus ring around it is the app's 12dp
        // corner: on a television the ripple and the ring were two different shapes on one
        // control. The ring is the one the design system fixes, so the button takes its corner.
        shape = KaeruTokens.ButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = KaeruSecondary),
        contentPadding = PaddingValues(horizontal = KaeruTokens.Space3),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(KaeruTokens.Space1))
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(ChevronSize))
        }
    }
}
