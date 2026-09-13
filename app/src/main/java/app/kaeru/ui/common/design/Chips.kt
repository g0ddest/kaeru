package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
 * The mark on a track this viewer keeps coming back to, wherever tracks are listed.
 *
 * A chip rather than another line of caption: the row already says what kind of track it is and
 * how long, and a second grey line under the first would read as more of the same. It never takes
 * the accent — amber on this screen means «Смотреть» — so the hint stays a hint and the row the
 * anime already remembers keeps the only tick on the list.
 */
@Composable
fun OftenChosenChip(modifier: Modifier = Modifier) = MetaChip(OFTEN_CHOSEN, modifier)

/**
 * The current list status, and the way to change it.
 *
 * Unlike [MetaChip] this is a control, so it is a full 48dp tall and carries an affordance saying
 * a menu is behind it. Set [affordance] false where nothing opens — a tab, a single-choice row, a
 * chip that is the whole action — because a chevron there promises a menu that does not exist.
 * [trailing] puts something else in that place instead; the default chevron is decorative, since
 * the pill's own label is what gets read out.
 *
 * [role] also decides how the pill announces itself. A tab or a menu anchor has a selected state
 * worth reading out; a plain button does not, and a recent search query announced as «not
 * selected» would be telling a screen reader about a state the chip does not have.
 */
@Composable
fun StatusPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    affordance: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .kaeruFocus(
                shape = KaeruTokens.ChipShape,
                // Amber on amber would not read; a selected pill rings in text colour instead.
                borderColor = if (selected) KaeruText else KaeruAccent,
            )
            .clip(KaeruTokens.ChipShape)
            .background(if (selected) KaeruAccent else KaeruElevated)
            .then(
                if (role == Role.Button) {
                    Modifier.clickable(onClick = onClick, role = role)
                } else {
                    Modifier.selectable(selected = selected, role = role, onClick = onClick)
                },
            )
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
        } else if (affordance) {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (selected) KaeruOnAccent else KaeruText,
                modifier = Modifier.size(AffordanceSize),
            )
        }
    }
}
