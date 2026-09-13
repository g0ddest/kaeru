package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruText

private val ChipPaddingH = KaeruTokens.Space3
private val ChipPaddingV = 7.dp
private val AffordanceSize = 20.dp

/**
 * One fact about a title: a year, a season length, a score, a studio.
 *
 * Metadata comes as chips precisely so it never has to be joined into one string with separators.
 * Several of these in a row read as several facts; `2026 · 12 серий · MAPPA` reads as boilerplate.
 */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = KaeruText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(KaeruTokens.ChipShape)
            .background(KaeruElevated)
            .padding(horizontal = ChipPaddingH, vertical = ChipPaddingV),
    )
}

/**
 * The current list status, and the way to change it.
 *
 * Unlike [MetaChip] this is a control, so it is a full 48dp tall and carries an affordance saying
 * a menu is behind it. [trailing] replaces that affordance when a screen needs something else
 * there; the default chevron is decorative, since the pill's own label is what gets read out.
 */
@Composable
fun StatusPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .clip(KaeruTokens.ChipShape)
            .background(if (selected) KaeruAccent else KaeruElevated)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { this.selected = selected }
            .padding(horizontal = KaeruTokens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space1),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) KaeruOnAccent else KaeruText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (selected) KaeruOnAccent else KaeruText,
                modifier = Modifier.size(AffordanceSize),
            )
        }
    }
}
