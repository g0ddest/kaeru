package app.kaeru.ui.mobile.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.download.DownloadQualityChoice
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.downloadChoiceLabel
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.design.pluralEpisodesAccusative
import app.kaeru.ui.common.details.DownloadChoice
import app.kaeru.ui.common.settings.SettingChoiceRow
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
private const val SEEN = "Просмотренные"
private const val QUALITY = "Качество"
private const val NOTHING = "Все вышедшие серии уже на устройстве"

/**
 * What the viewer can ask of this download's height. 1080p is left out: it is a phone, and it is a
 * limit.
 *
 * The first chip is a choice of its own rather than an absent one — «как при просмотре» is a
 * promise about the picture, and it reaches the engine as [DownloadQualityChoice.FollowPlayback]
 * instead of as a null that the download settings would then answer.
 */
private val QUALITIES: List<DownloadQualityChoice> = listOf(
    DownloadQualityChoice.FollowPlayback,
    DownloadQualityChoice.Fixed(Quality.P360),
    DownloadQualityChoice.Fixed(Quality.P480),
    DownloadQualityChoice.Fixed(Quality.P720),
)

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
    onDownload: (episodes: List<Int>, quality: DownloadQualityChoice) -> Unit,
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
    onDownload: (List<Int>, DownloadQualityChoice) -> Unit,
) {
    // Keyed on the episodes themselves rather than on how many there are: one episode finishing
    // while another is offered leaves the count the same and the numbers different, and a ticked
    // set that survived that would be ticking episodes nobody chose.
    val offered = choices.map { it.episode }
    var picked by rememberSaveable(offered, stateSaver = EpisodesSaver) {
        mutableStateOf(choices.filterNot { it.watched }.map { it.episode }.toSet())
    }
    // Opens on whatever the download settings say, as the chip the viewer would have chosen: a
    // policy with no height of its own is the same sentence this sheet's first chip says.
    var height by rememberSaveable(quality, stateSaver = ChoiceSaver) {
        mutableStateOf(quality?.let(DownloadQualityChoice::Fixed) ?: DownloadQualityChoice.FollowPlayback)
    }
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
        val toggle: (DownloadChoice) -> Unit = { choice ->
            picked = if (choice.episode in picked) picked - choice.episode else picked + choice.episode
        }
        // What the sheet opens ticked goes first, and the rest sit under a word saying why they are
        // not. The list used to run in episode order, so a viewer on episode ten met five watched
        // episodes and a button already promising «Скачать 1 серию» about a tick below the fold —
        // a summary that could not be checked against anything on screen.
        val (unwatched, watched) = sheetSections(choices)
        LazyColumn(Modifier.heightIn(max = ListHeight)) {
            items(unwatched, key = { it.episode }) { choice ->
                EpisodeChoiceRow(choice, checked = choice.episode in picked) { toggle(choice) }
            }
            if (watched.isNotEmpty() && unwatched.isNotEmpty()) {
                item(key = SEEN) {
                    Text(
                        SEEN,
                        style = MaterialTheme.typography.labelMedium,
                        color = KaeruSecondary,
                        modifier = Modifier.padding(
                            start = KaeruTokens.GutterPhone,
                            top = KaeruTokens.Space4,
                            bottom = KaeruTokens.Space1,
                        ),
                    )
                }
            }
            items(watched, key = { it.episode }) { choice ->
                EpisodeChoiceRow(
                    choice,
                    checked = choice.episode in picked,
                    underHeading = unwatched.isNotEmpty(),
                ) { toggle(choice) }
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
private fun EpisodeChoiceRow(
    choice: DownloadChoice,
    checked: Boolean,
    underHeading: Boolean = false,
    onToggle: () -> Unit,
) {
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
        // Only where there is no heading above to say it — a season with nothing left unwatched
        // lists everything in one block, and the rows are then the only place it can be said.
        if (choice.watched && !underHeading) {
            Text(WATCHED, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
        }
    }
}

@Composable
private fun QualityRow(quality: DownloadQualityChoice, onPick: (DownloadQualityChoice) -> Unit) {
    Column(Modifier.padding(top = KaeruTokens.Space3)) {
        Text(
            QUALITY,
            style = MaterialTheme.typography.labelMedium,
            color = KaeruSecondary,
            modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone),
        )
        // Wrapped rather than scrolled. Four chips and «Как при просмотре» among them do not fit a
        // phone's width, and a row that scrolled left the last one cut off at the edge with nothing
        // saying it was there — a choice a viewer cannot see is a choice they do not have. This is
        // also how the same chips are drawn in the settings.
        Box(Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2)) {
            SettingChoiceRow(
                options = QUALITIES,
                label = ::downloadChoiceLabel,
                selected = { it == quality },
                onSelect = onPick,
            )
        }
    }
}

/**
 * A viewer part way through a season, which is the case that was wrong on a real phone: the
 * episodes the sheet opens ticked have to be the ones at the top, or the button counts something
 * nobody can see.
 */
private val sample = listOf(
    DownloadChoice(5, watched = true),
    DownloadChoice(6, watched = true),
    DownloadChoice(7, watched = false),
    DownloadChoice(8, watched = false),
)

/** A rewatch: nothing left unwatched, so there is no heading and the rows say it themselves. */
private val allSeen = (1..4).map { DownloadChoice(it, watched = true) }

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 560)
@Composable
private fun DownloadSheetPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(sample, 320L * 1024 * 1024, Quality.P720) { _, _ -> }
    }
}

/**
 * The narrowest phone this app is drawn for. The quality chips wrap onto a second line here, which
 * is the whole point of them wrapping: on a 1080-wide device the last one used to sit off the edge.
 */
@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 320, heightDp = 580)
@Composable
private fun DownloadSheetNarrowPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(sample, 320L * 1024 * 1024, null) { _, _ -> }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 560)
@Composable
private fun DownloadSheetAllWatchedPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(allSeen, 320L * 1024 * 1024, Quality.P480) { _, _ -> }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 200)
@Composable
private fun DownloadSheetNothingPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        DownloadSheetContent(emptyList(), 320L * 1024 * 1024, null) { _, _ -> }
    }
}

/**
 * The two blocks the list is drawn in: what the sheet opens ticked, then what it does not.
 *
 * Extracted from the composition so the rule can be read in a test. It is the one the button's
 * summary depends on — «Скачать 1 серию» has to be about a tick the viewer can see, and in episode
 * order on a season somebody is part way through, that tick is below the fold under five episodes
 * they have already watched.
 */
internal fun sheetSections(choices: List<DownloadChoice>): Pair<List<DownloadChoice>, List<DownloadChoice>> =
    choices.partition { !it.watched }

/**
 * Twelve ticked episodes survive a rotation, which is the difference between a phone turning and a
 * viewer starting again. A `Set<Int>` is not something a `Bundle` holds, so it travels as the array
 * it can hold.
 */
private val EpisodesSaver: Saver<Set<Int>, IntArray> =
    Saver(save = { it.toIntArray() }, restore = { it.toSet() })

/** The same, for the one chip: the height as a number, or a marker for «как при просмотре». */
private const val FOLLOW_PLAYBACK = -1

private val ChoiceSaver: Saver<DownloadQualityChoice, Int> = Saver(
    save = { choice ->
        when (choice) {
            DownloadQualityChoice.FollowPlayback -> FOLLOW_PLAYBACK
            is DownloadQualityChoice.Fixed -> choice.quality.height
        }
    },
    restore = { height ->
        Quality.ofHeight(height)?.let(DownloadQualityChoice::Fixed) ?: DownloadQualityChoice.FollowPlayback
    },
)
