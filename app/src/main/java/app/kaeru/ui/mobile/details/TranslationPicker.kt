package app.kaeru.ui.mobile.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.OftenChosenChip
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.kaeruFocus
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

private const val DUB = "Озвучка"
private const val SUBTITLES = "Субтитры"
private const val CHOSEN = "Выбрано"
private const val NONE = "Источник не предложил ни одной озвучки для этого аниме"

/** Tall enough to show four tracks without the sheet swallowing the screen. */
private val ListHeight = 360.dp
private val RowBlock = 20.dp
private const val SKELETON_ROWS = 3

/**
 * Which voice this anime plays in.
 *
 * The list arrives ranked — the remembered track first, then the studios the viewer put at the top
 * of their settings, then the ones they keep choosing elsewhere — so the order is itself the
 * recommendation and the first row is almost always the right one. Picking a track writes it
 * against this anime and nothing else: the episode and the position stay where they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationPickerSheet(
    translations: List<RankedTranslation>,
    currentId: Int?,
    loading: Boolean,
    errorMessage: String?,
    /** False while the last pick is still being written, so the same row cannot be sent twice. */
    enabled: Boolean,
    onRetry: () -> Unit,
    onPick: (Translation) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        RowHeader(DUB)
        when {
            loading -> LoadingTracks()
            errorMessage != null -> ErrorState(errorMessage, onRetry)
            translations.isEmpty() -> Text(
                NONE,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                modifier = Modifier.padding(
                    horizontal = KaeruTokens.GutterPhone,
                    vertical = KaeruTokens.Space4,
                ),
            )
            else -> LazyColumn(Modifier.heightIn(max = ListHeight)) {
                items(translations, key = { it.translation.id }) { ranked ->
                    TrackRow(
                        ranked.translation,
                        selected = ranked.translation.id == currentId,
                        oftenChosen = ranked.oftenChosen,
                        enabled = enabled,
                        onClick = { onPick(ranked.translation) },
                    )
                }
            }
        }
        Spacer(Modifier.height(KaeruTokens.Space6))
    }
}

@Composable
private fun LoadingTracks() = SkeletonGroup {
    Column(
        Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        repeat(SKELETON_ROWS) { Skeleton(Modifier.fillMaxWidth(0.6f).height(RowBlock)) }
    }
}

@Composable
private fun TrackRow(
    track: Translation,
    selected: Boolean,
    oftenChosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .kaeruFocus(KaeruTokens.CardShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
            ) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) KaeruText else KaeruSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Only where the anime has nothing remembered: the row it does remember carries
                // the tick, and one list saying two things at once says neither.
                if (oftenChosen) OftenChosenChip()
            }
            Text(
                caption(track),
                style = MaterialTheme.typography.labelMedium,
                color = KaeruSecondary,
                maxLines = 1,
            )
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = CHOSEN, tint = KaeruAccent)
    }
}

/**
 * What kind of track this is, and how much of the season it covers.
 *
 * Two phrases joined by a comma rather than by a middle dot: «Озвучка, 12 серий» reads as a
 * sentence, and a track whose length the source never counted simply says what it is.
 */
private fun caption(track: Translation): String {
    val kind = if (track.type == TranslationKind.SUBTITLES) SUBTITLES else DUB
    val episodes = track.episodesCount?.takeIf { it > 0 } ?: return kind
    return "$kind, ${pluralEpisodes(episodes)}"
}
