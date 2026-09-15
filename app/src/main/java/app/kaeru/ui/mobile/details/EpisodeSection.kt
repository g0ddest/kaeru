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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.download.DownloadState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.RowAction
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.pluralEpisodesAccusative
import app.kaeru.ui.common.details.COLLAPSE
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.details.watchedLine
import app.kaeru.ui.common.downloads.DownloadMark
import app.kaeru.ui.common.downloads.downloadMark
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private const val EPISODES = "Серии"
private const val MARK_WATCHED = "Отметить просмотренной"
private const val WATCHED = "Просмотрено"
private const val NOT_AIRED = "не вышла"
private const val WATCH = "Смотреть"
private const val DOWNLOAD_SOME = "Скачать…"
private const val DOWNLOAD = "Скачать"
private const val REMOVE_DOWNLOAD = "Удалить загрузку"
private const val QUEUED = "В очереди"
private const val DOWNLOADING = "Загружается"
private const val DOWNLOADED = "Скачано"
private const val FAILED = "Не скачалась"
private const val MORE_ACTIONS = "Что сделать с серией"
private const val NO_NETWORK = "Нет сети"

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

/** The download mark in the opposite corner from the watched check, and the ring that replaces it. */
private val DownloadGlyph = 14.dp
private val RingSize = 12.dp
private val RingStroke = 2.dp

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
    offline: Boolean,
    onPlay: (Int) -> Unit,
    onMarkWatched: (Int) -> Unit,
    onDownloadSome: () -> Unit,
    onDownload: (Int) -> Unit,
    onRemoveDownload: (Int) -> Unit,
) {
    if (cells.isEmpty()) return
    var visible by remember(cells.size) { mutableIntStateOf(minOf(EPISODE_PAGE, cells.size)) }
    // Which cell is under a long press, remembered here rather than in every cell: one menu exists
    // in the composition at a time, anchored on the tile that asked for it.
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val remaining = cells.size - visible
    // Offered only where there is something to offer: a season entirely on the device, or one that
    // has not aired, would open a sheet with an empty list in it.
    val downloadable = cells.any { it.aired && (it.download == null || it.download == DownloadState.FAILED) }
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        RowHeader(
            EPISODES,
            // No chevron: this opens a sheet over the screen rather than leading somewhere, and
            // the ellipsis already says a choice is coming.
            //
            // With no network it says so in place of the verb and stops accepting presses. A
            // download starts by resolving a link, so offline the sheet could only end in a
            // failure — and the honest moment to say that is before the viewer has ticked eight
            // episodes, not after.
            action = when {
                !downloadable -> null
                offline -> RowAction(NO_NETWORK, icon = null, enabled = false, onClick = {})
                else -> RowAction(DOWNLOAD_SOME, icon = null, onClick = onDownloadSome)
            },
        )
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
                            offline = offline,
                            menuOpen = menuFor == cell.number,
                            onPlay = { onPlay(cell.number) },
                            onLongPress = { menuFor = cell.number },
                            onDismissMenu = { menuFor = null },
                            onMarkWatched = {
                                menuFor = null
                                onMarkWatched(cell.number)
                            },
                            onDownload = {
                                menuFor = null
                                onDownload(cell.number)
                            },
                            onRemoveDownload = {
                                menuFor = null
                                onRemoveDownload(cell.number)
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
 * One episode, saying four different things four different ways.
 *
 * The check in the top right is Shikimori's count, the mark in the top left is where the episode
 * is stored, the strip along the bottom is where this device stopped inside it, and a dimmed tile
 * is an episode that has not aired. The two corners are deliberately opposite ends: «просмотрено»
 * and «скачано» are different kinds of fact about one episode, and a viewer should be able to read
 * either without first working out which mark this one is.
 *
 * A long press opens what can be done with it — play it, keep it, give the space back, count it as
 * watched — with each entry there only when it means something.
 */
@Composable
private fun EpisodeTile(
    cell: EpisodeCell,
    modifier: Modifier,
    offline: Boolean,
    menuOpen: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onMarkWatched: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
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
                    // Every aired episode has something on the menu now — at the very least it can
                    // be downloaded — where before only an uncounted one did.
                    onLongClick = if (cell.aired) onLongPress else null,
                    onLongClickLabel = if (cell.aired) MORE_ACTIONS else null,
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
                DownloadCorner(
                    cell,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(top = KaeruTokens.Space1, start = KaeruTokens.Space1),
                )
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
                // What can be done with the episode, before what can be said about it: playing it
                // is what the tile itself does, and this menu exists for the rest.
                when (cell.download) {
                    // Offline it stays on the list and stops working, with the reason beside it —
                    // an entry that vanished with the network would look like a bug.
                    null, DownloadState.FAILED -> MenuItem(
                        DOWNLOAD,
                        onDownload,
                        enabled = !offline,
                        hint = NO_NETWORK.takeIf { offline },
                    )
                    DownloadState.REMOVING -> Unit
                    else -> MenuItem(REMOVE_DOWNLOAD, onRemoveDownload)
                }
                MenuItem(WATCH, onPlay)
                if (markable) MenuItem(MARK_WATCHED, onMarkWatched)
            }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    hint: String? = null,
) = DropdownMenuItem(
    text = { Text(text, style = MaterialTheme.typography.titleSmall) },
    onClick = onClick,
    enabled = enabled,
    // The one place a menu entry says why it cannot be pressed. It sits where a submenu chevron
    // would, which is where the eye goes after the verb.
    trailingIcon = hint?.let {
        { Text(it, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary) }
    },
    colors = MenuDefaults.itemColors(
        textColor = KaeruText,
        disabledTextColor = KaeruSecondary,
    ),
)

/**
 * Where this episode is: on its way, on the device, or refused.
 *
 * A running download is the one that takes the accent, and it takes it as a ring rather than as
 * another shape — amber means «how far along something is» everywhere else in this app, and the
 * bottom of the tile is already spoken for by how far the viewer got inside the episode. A queue
 * that is waiting for Wi-Fi draws the same arrow as one that is simply waiting its turn: both are
 * «скоро», and the difference between them belongs on the «Загрузки» screen, where it can be a
 * sentence.
 */
@Composable
private fun DownloadCorner(cell: EpisodeCell, modifier: Modifier) {
    when (downloadMark(cell.download)) {
        DownloadMark.NONE -> Unit
        DownloadMark.PENDING -> Icon(
            Icons.Default.Download,
            contentDescription = QUEUED,
            tint = KaeruSecondary,
            modifier = modifier.size(DownloadGlyph),
        )
        DownloadMark.RUNNING -> CircularProgressIndicator(
            progress = { cell.downloadProgress ?: 0f },
            modifier = modifier.size(RingSize).semantics { contentDescription = DOWNLOADING },
            color = KaeruAccent,
            trackColor = KaeruSecondary.copy(alpha = 0.3f),
            strokeWidth = RingStroke,
            gapSize = 0.dp,
        )
        DownloadMark.DONE -> Icon(
            Icons.Default.DownloadDone,
            contentDescription = DOWNLOADED,
            tint = KaeruText,
            modifier = modifier.size(DownloadGlyph),
        )
        DownloadMark.FAILED -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = FAILED,
            tint = KaeruError,
            modifier = modifier.size(DownloadGlyph),
        )
    }
}
