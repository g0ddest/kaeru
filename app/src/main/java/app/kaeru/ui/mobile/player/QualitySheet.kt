package app.kaeru.ui.mobile.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

/**
 * The quality chooser, and the one place a viewer can settle the question for good.
 *
 * The switch is here rather than in settings because this is where they are standing when they
 * find out what their connection will carry: the episode stalls, they open this, and the decision
 * they make is about every episode, not only this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    qualities: List<Quality>,
    current: Quality?,
    remembered: Boolean,
    onRemember: (Boolean) -> Unit,
    onPick: (Quality) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        RowHeader("Качество")
        qualities.sortedByDescending { it.height }.forEach { quality ->
            QualityRow("${quality.height}p", selected = quality == current, onClick = { onPick(quality) })
        }
        HorizontalDivider(
            color = KaeruDivider,
            modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
        )
        RememberQualityRow(remembered, onRemember)
        Spacer(Modifier.height(KaeruTokens.Space6))
    }
}

@Composable
private fun RememberQualityRow(remembered: Boolean, onRemember: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .toggleable(value = remembered, role = Role.Switch, onValueChange = onRemember)
            .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Запоминать качество", style = MaterialTheme.typography.titleSmall, color = KaeruText)
            Text(
                "Новые серии будут открываться в нём",
                style = MaterialTheme.typography.labelMedium,
                color = KaeruSecondary,
            )
        }
        Switch(
            checked = remembered,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KaeruOnAccent,
                checkedTrackColor = KaeruAccent,
                checkedBorderColor = KaeruAccent,
            ),
        )
    }
}

@Composable
private fun QualityRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = KaeruText, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = "Выбрано", tint = KaeruAccent)
    }
}
