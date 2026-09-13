package app.kaeru.ui.mobile.details

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.pluralEpisodesAccusative
import app.kaeru.ui.common.details.COLLAPSE
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.details.watchedLine
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private const val EPISODES = "Серии"
private const val MARK_WATCHED = "Отметить просмотренной"
private const val WATCHED = "Просмотрено"
private const val NOT_AIRED = "не вышла"
private const val WATCH = "Смотреть"

private const val EPISODE_COLUMNS = 5

/**
 * How many tiles are composed at once.
 *
 * The grid is a plain column inside the page's own scroll, so every tile in it is measured and
 * laid out in one frame. Sixty is about a screen and a half — enough that an ordinary season is
 * whole and an unusual one stays affordable. One Piece would otherwise put a thousand tiles
 * through layout the moment the viewer asked for the rest.
 */
private const val EPISODE_PAGE = 60

private val EpisodeCellHeight = 56.dp
private val CheckSize = 14.dp

/**
 * The season as a grid rather than a list.
 *
 * Twenty-eight numbers in six rows can be read at a glance — which are behind you, which one you
 * are in the middle of, where the season stops; twenty-eight list rows cannot. A long season
 * arrives sixty at a time, and folds back to sixty when the viewer is done with it.
 */
@Composable
internal fun EpisodeSection(
    cells: List<EpisodeCell>,
    watched: Int,
    onPlay: (Int) -> Unit,
    onMarkWatched: (Int) -> Unit,
) {
    if (cells.isEmpty()) return
    var visible by remember(cells.size) { mutableIntStateOf(minOf(EPISODE_PAGE, cells.size)) }
    // Which cell is under a long press, remembered here rather than in every cell: one menu exists
    // in the composition at a time, anchored on the tile that asked for it.
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val remaining = cells.size - visible
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        RowHeader(EPISODES)
        Text(
            // The grid itself is the total: it already draws every episode there turned out to be.
            watchedLine(watched, cells.size),
            style = MaterialTheme.typography.labelMedium,
            color = KaeruSecondary,
            modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space1),
        )
        Column(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            cells.take(visible).chunked(EPISODE_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    row.forEach { cell ->
                        EpisodeTile(
                            cell = cell,
                            modifier = Modifier.weight(1f),
                            menuOpen = menuFor == cell.number,
                            onPlay = { onPlay(cell.number) },
                            onLongPress = { menuFor = cell.number },
                            onDismissMenu = { menuFor = null },
                            onMarkWatched = {
                                menuFor = null
                                onMarkWatched(cell.number)
                            },
                        )
                    }
                    // The last row keeps the pitch of the ones above it rather than stretching.
                    repeat(EPISODE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        when {
            // The label counts what pressing it will actually add, so it is never a promise of
            // «all» that turns out to be sixty.
            remaining > 0 -> {
                val next = minOf(EPISODE_PAGE, remaining)
                TextAction(
                    // «Показать» takes the accusative: «ещё 21 серию», never «ещё 21 серия».
                    "Показать ещё ${pluralEpisodesAccusative(next)}",
                    { visible += next },
                    Modifier.padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3),
                )
            }
            cells.size > EPISODE_PAGE -> TextAction(
                COLLAPSE,
                { visible = EPISODE_PAGE },
                Modifier.padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3),
            )
        }
    }
}

/**
 * One episode, saying three different things three different ways.
 *
 * The check is Shikimori's count, the strip is where this device stopped, and a dimmed tile is an
 * episode that has not aired. A long press offers to mark it — but only on an episode that is out
 * and not already counted, since there is nothing honest to do on either of the others.
 */
@Composable
private fun EpisodeTile(
    cell: EpisodeCell,
    modifier: Modifier,
    menuOpen: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onMarkWatched: () -> Unit,
) {
    val markable = cell.aired && !cell.watched
    Box(modifier) {
        Column(
            Modifier
                .height(EpisodeCellHeight)
                .clip(KaeruTokens.CardShape)
                .background(if (cell.aired) KaeruElevated else KaeruElevated.copy(alpha = 0.45f))
                .combinedClickable(
                    enabled = cell.aired,
                    onClick = onPlay,
                    onClickLabel = WATCH,
                    onLongClick = if (markable) onLongPress else null,
                    onLongClickLabel = if (markable) MARK_WATCHED else null,
                )
                // One spoken sentence instead of a number and a fragment read separately.
                .then(
                    if (cell.aired) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { contentDescription = "${cell.number} серия, $NOT_AIRED" }
                    },
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        cell.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = when {
                            !cell.aired -> KaeruSecondary.copy(alpha = 0.6f)
                            cell.watched -> KaeruSecondary
                            else -> KaeruText
                        },
                    )
                    if (!cell.aired) {
                        Text(
                            NOT_AIRED,
                            style = MaterialTheme.typography.labelSmall,
                            color = KaeruSecondary.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (cell.watched) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = WATCHED,
                        tint = KaeruAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = KaeruTokens.Space1, end = KaeruTokens.Space1)
                            .size(CheckSize),
                    )
                }
            }
            cell.progress?.let { ProgressStrip(it) }
        }
        if (menuOpen) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = onDismissMenu,
                shape = KaeruTokens.CardShape,
                containerColor = KaeruElevated,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                DropdownMenuItem(
                    text = { Text(MARK_WATCHED, style = MaterialTheme.typography.titleSmall) },
                    onClick = onMarkWatched,
                    colors = MenuDefaults.itemColors(textColor = KaeruText),
                )
            }
        }
    }
}
