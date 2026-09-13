package app.kaeru.ui.mobile.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.OftenChosenChip
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSurface

/**
 * The track chooser. The list arrives ranked — remembered choice first, then the viewer's
 * preferred studios, then the ones they keep choosing elsewhere — so the order itself is the
 * recommendation, and the chip repeats the strongest part of it for the eye.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSheet(
    translations: List<RankedTranslation>,
    currentId: Int?,
    onPick: (Translation) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        SheetTitle("Озвучка")
        if (translations.isEmpty()) {
            Text(
                "Источник не предложил ни одной озвучки",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(translations, key = { it.translation.id }) { ranked ->
                SheetRow(
                    title = ranked.translation.title,
                    caption = caption(ranked.translation),
                    selected = ranked.translation.id == currentId,
                    oftenChosen = ranked.oftenChosen,
                    onClick = { onPick(ranked.translation) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

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
        SheetTitle("Качество")
        qualities.sortedByDescending { it.height }.forEach { quality ->
            SheetRow(
                title = "${quality.height}p",
                caption = null,
                selected = quality == current,
                onClick = { onPick(quality) },
            )
        }
        HorizontalDivider(
            color = KaeruDivider,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        RememberQualityRow(remembered, onRemember)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RememberQualityRow(remembered: Boolean, onRemember: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = remembered, role = Role.Switch, onValueChange = onRemember)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Запоминать качество", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Новые серии будут открываться в нём",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetRow(
    title: String,
    caption: String?,
    selected: Boolean,
    oftenChosen: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (oftenChosen) OftenChosenChip()
            }
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Выбрано", tint = KaeruAccent)
    }
}

private fun caption(track: Translation): String? {
    val kind = if (track.type == TranslationKind.SUBTITLES) "Субтитры" else "Озвучка"
    val episodes = track.episodesCount?.takeIf { it > 0 }?.let { "$it серий" }
    return listOfNotNull(kind, episodes).joinToString("   ")
}
