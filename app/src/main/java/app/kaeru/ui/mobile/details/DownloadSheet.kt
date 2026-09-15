package app.kaeru.ui.mobile.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.downloadQualityLabel
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.design.pluralEpisodesAccusative
import app.kaeru.ui.common.details.DownloadChoice
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

private const val TITLE = "Скачать серии"
private const val ALL_AIRED = "Все вышедшие"
private const val UNWATCHED = "Непросмотренные"
private const val WATCHED = "просмотрено"
private const val QUALITY = "Качество"
private const val NOTHING = "Все вышедшие серии уже на устройстве"

/** The heights a download is offered at. 1080p is left out: it is a phone, and it is a limit. */
private val QUALITIES: List<Quality?> = listOf(null, Quality.P360, Quality.P480, Quality.P720)

/** Tall enough for six episodes; past that the list scrolls inside the sheet. */
private val ListHeight = 300.dp

/**
 * Which episodes to keep on the device, and at what height.
 *
 * It opens on the episodes the viewer has not seen, because that is what somebody packing for a
 * flight means by «скачать серии» — the two shortcuts above the list are there for when it is not.
 * Episodes already on the device are not in the list at all: a checkbox for something that is
 * already done is a checkbox that can only be wrong.
 *
 * The button counts what pressing it will do and what that will cost, so the size is read before
 * the download starts rather than in a notification afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSheet(
    choices: List<DownloadChoice>,
    estimateBytes: Long,
    quality: Quality?,
    onDownload: (episodes: List<Int>, quality: Quality?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        DownloadSheetContent(choices, estimateBytes, quality, onDownload)
    }
}

/**
 * The sheet's contents as a plain column.
 *
 * Apart from the sheet itself so that a preview can draw it: `ModalBottomSheet` renders into a
 * window of its own and previews as an empty screen.
 */
@Composable
private fun DownloadSheetContent(
    choices: List<DownloadChoice>,
    estimateBytes: Long,
    quality: Quality?,
    onDownload: (List<Int>, Quality?) -> Unit,
) {
    // Plain `remember`: a set of episode numbers is not something a Bundle can hold, and the sheet
    // is dismissed by a configuration change anyway.
    var picked by remember(choices.size) {
        mutableStateOf(choices.filterNot { it.watched }.map { it.episode }.toSet())
    }
    var height by remember(quality) { mutableStateOf(quality) }
    Column(Modifier.padding(bottom = KaeruTokens.Space6)) {
        RowHeader(TITLE)
        if (choices.isEmpty()) {
            Text(
                NOTHING,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space4),
            )
            return@Column
        }
        Row(Modifier.padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3)) {
            TextAction(ALL_AIRED, { picked = choices.map { it.episode }.toSet() })
            TextAction(UNWATCHED, { picked = choices.filterNot { it.watched }.map { it.episode }.toSet() })
        }
        LazyColumn(Modifier.heightIn(max = ListHeight)) {
            items(choices, key = { it.episode }) { choice ->
                EpisodeChoiceRow(
                    choice = choice,
                    checked = choice.episode in picked,
                    onToggle = {
                        picked = if (choice.episode in picked) picked - choice.episode else picked + choice.episode
                    },
                )
            }
        }
        QualityRow(height) { height = it }
        PrimaryButton(
            text = buttonLabel(picked.size, estimateBytes),
            onClick = { onDownload(picked.sorted(), height) },
            enabled = picked.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
        )
    }
}

/**
 * «Скачать 3 серии (~1,2 ГБ)» — what will happen and what it will cost.
 *
 * The tilde is doing real work: the size is an average of what this device has downloaded before,
 * and an exact-looking number that turned out to be 200 MB off would be worse than no number.
 * Nothing selected leaves the verb alone, since there is nothing to count.
 */
private fun buttonLabel(count: Int, estimateBytes: Long): String {
    if (count <= 0) return "Скачать"
    return "Скачать ${pluralEpisodesAccusative(count)} (~${formatBytes(estimateBytes * count)})"
}

@Composable
private fun EpisodeChoiceRow(choice: DownloadChoice, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        Checkbox(
            checked = checked,
            // The row is the target; a second one inside it would be announced twice.
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = KaeruAccent,
                checkmarkColor = KaeruOnAccent,
                uncheckedColor = KaeruDivider,
            ),
        )
        Text(
            "${choice.episode} серия",
            style = MaterialTheme.typography.titleSmall,
            color = KaeruText,
            modifier = Modifier.weight(1f),
        )
        // Said quietly rather than hidden: a viewer downloading a show to watch again needs to see
        // which episodes those are, and «Непросмотренные» above needs something to select on.
        if (choice.watched) {
            Text(WATCHED, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
        }
    }
}

@Composable
private fun QualityRow(quality: Quality?, onPick: (Quality?) -> Unit) {
    Column(Modifier.padding(top = KaeruTokens.Space3)) {
        Text(
            QUALITY,
            style = MaterialTheme.typography.labelMedium,
            color = KaeruSecondary,
            modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone),
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            QUALITIES.forEach { option ->
                StatusPill(
                    text = downloadQualityLabel(option),
                    selected = option == quality,
                    onClick = { onPick(option) },
                    role = Role.RadioButton,
                    affordance = false,
                )
            }
        }
    }
}

private val sample = listOf(
    DownloadChoice(5, watched = true),
    DownloadChoice(6, watched = true),
    DownloadChoice(7, watched = false),
    DownloadChoice(8, watched = false),
)

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 560)
@Composable
private fun DownloadSheetPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(sample, 320L * 1024 * 1024, Quality.P720) { _, _ -> }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 200)
@Composable
private fun DownloadSheetNothingPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(emptyList(), 320L * 1024 * 1024, null) { _, _ -> }
    }
}
