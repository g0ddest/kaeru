package app.kaeru.ui.mobile.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.DestructiveButton
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.PosterImage
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.downloadLimitLabel
import app.kaeru.ui.common.design.downloadQualityLabel
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.downloads.DownloadedTitle
import app.kaeru.ui.common.downloads.DownloadsUiState
import app.kaeru.ui.common.downloads.downloadStateLine
import app.kaeru.ui.common.settings.SettingChoiceRow
import app.kaeru.ui.common.settings.SettingLabel
import app.kaeru.ui.common.settings.SettingNote
import app.kaeru.ui.common.settings.SettingSwitchRow
import app.kaeru.ui.common.settings.SettingsSection
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

private const val TITLE = "Загрузки"
private const val BACK = "Назад"
private const val SETTINGS = "Настройки загрузок"
private const val QUALITY = "Качество загрузки"
private const val QUALITY_NOTE = "Чем ниже качество, тем больше серий поместится"
private const val WIFI_ONLY = "Только по Wi-Fi"
private const val DELETE_WATCHED = "Удалять просмотренные"
private const val DELETE_WATCHED_NOTE = "Серия освобождает место, как только Shikimori её засчитал"
private const val LIMIT = "Лимит места"
private const val LIMIT_NOTE = "Когда место закончится, новые загрузки не начнутся"
private const val CLEAR_ALL = "Очистить все загрузки"
private const val CLEAR_TITLE = "Удалить все загрузки?"
private const val CLEAR_CONFIRM = "Удалить"
private const val CLEAR_CANCEL = "Отмена"
private const val REMOVE_TITLE = "Удалить загрузки тайтла"
private const val REMOVE_TITLE_ASK = "Удалить загрузки тайтла?"
private const val REMOVE_EPISODE = "Удалить загрузку"
private const val EXPAND = "Показать серии"
private const val COLLAPSE = "Свернуть серии"
private const val EMPTY_TITLE = "Пока ничего не скачано"
private const val EMPTY_TEXT =
    "Откройте аниме, нажмите «Скачать…» над списком серий — и они будут доступны без сети."

/** The heights a download is offered at, as on the sheet: 1080p is a phone's storage, not a setting. */
private val QUALITIES: List<Quality?> = listOf(null, Quality.P360, Quality.P480, Quality.P720)

private const val GB = 1024L * 1024 * 1024

/** What a viewer can give downloads: two, five, ten, twenty gigabytes, or the phone's own free space. */
private val LIMITS: List<Long?> = listOf(2 * GB, 5 * GB, 10 * GB, 20 * GB, null)

private val PosterWidth = 44.dp
private val PosterHeight = 66.dp

/**
 * What is on the device, and the rules that decide what lands here next.
 *
 * The screen answers one question — where has my storage gone — so it is ordered by that: the
 * total first, then what is taking it up, then the settings that change it, then the way to get
 * all of it back. The settings stay on screen when there is nothing downloaded: they are how a
 * viewer decides what the first download will cost.
 *
 * A title is a summary until it is opened. Somebody clearing space works title by title — six
 * headings with sizes are readable at a glance where forty episode rows are not — and the episodes
 * are one tap away for the times the answer is one episode rather than a show.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onRemove: (animeId: Int, episode: Int) -> Unit,
    onRemoveTitle: (animeId: Int) -> Unit,
    onRemoveAll: () -> Unit,
    onQuality: (Quality?) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDeleteWatched: (Boolean) -> Unit,
    onLimit: (Long?) -> Unit,
) {
    var clearing by rememberSaveable { mutableStateOf(false) }
    // Which titles are open, held for the screen rather than inside each row. The rows reorder
    // while a download runs — the newest change goes first — and per-row state is positional, so a
    // title that moved used to fold shut under the viewer.
    var expanded by rememberSaveable(stateSaver = TitlesSaver) { mutableStateOf(emptySet<Int>()) }
    // The title whose downloads are about to go, once the viewer has been asked. Not saveable on
    // purpose: a rotation closes the question rather than answering it, which is the safe way for
    // a prompt about deleting something to be interrupted.
    var removing by remember { mutableStateOf<DownloadedTitle?>(null) }
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(
            title = TITLE,
            navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, BACK, onBack) },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = KaeruTokens.Space2, bottom = KaeruTokens.Space8),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
        ) {
            UsageBlock(state)
            if (state.titles.isEmpty()) {
                EmptyState(EMPTY_TITLE, EMPTY_TEXT)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    state.titles.forEach { title ->
                        TitleBlock(
                            title = title,
                            expanded = title.animeId in expanded,
                            onToggle = {
                                expanded = if (title.animeId in expanded) {
                                    expanded - title.animeId
                                } else {
                                    expanded + title.animeId
                                }
                            },
                            onRemove = { episode -> onRemove(title.animeId, episode) },
                            onRemoveTitle = { removing = title },
                        )
                    }
                }
            }
            PolicySection(state.policy, onQuality, onWifiOnly, onDeleteWatched, onLimit)
            if (state.titles.isNotEmpty()) {
                DestructiveButton(
                    CLEAR_ALL,
                    onClick = { clearing = true },
                    modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone),
                )
            }
        }
    }
    removing?.let { title ->
        ConfirmRemovalDialog(
            title = REMOVE_TITLE_ASK,
            bytes = title.bytes,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                onRemoveTitle(title.animeId)
            },
        )
    }
    if (clearing) {
        ClearAllDialog(
            bytes = state.usedBytes,
            onDismiss = { clearing = false },
            onConfirm = {
                clearing = false
                onRemoveAll()
            },
        )
    }
}

/**
 * «Занято 3,2 ГБ из 5 ГБ», with the same 4dp strip the app draws every other proportion with.
 *
 * Without a limit there is no proportion to draw, so there is no bar — only the number. Past the
 * limit the strip turns red and stays full: nothing has been deleted, and the bar saying «over»
 * rather than «almost» is the difference between a viewer wondering why a download was refused and
 * knowing.
 */
@Composable
private fun UsageBlock(state: DownloadsUiState) {
    val limit = state.limitBytes
    Column(
        Modifier.padding(horizontal = KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        Text(
            if (limit == null) {
                "Занято ${formatBytes(state.usedBytes)}"
            } else {
                "Занято ${formatBytes(state.usedBytes)} из ${downloadLimitLabel(limit)}"
            },
            style = MaterialTheme.typography.titleMedium,
            color = KaeruText,
        )
        if (limit != null && limit > 0) {
            ProgressStrip(
                progress = state.usedBytes.toFloat() / limit,
                color = if (state.overLimit) KaeruError else KaeruAccent,
            )
        }
    }
}

@Composable
private fun TitleBlock(
    title: DownloadedTitle,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: (Int) -> Unit,
    onRemoveTitle: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) COLLAPSE else EXPAND, onClick = onToggle)
                .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterImage(
                url = title.posterUrl,
                title = title.title,
                modifier = Modifier.size(PosterWidth, PosterHeight),
            )
            Spacer(Modifier.width(KaeruTokens.Space3))
            Column(Modifier.weight(1f)) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = KaeruText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Two facts, joined by a comma rather than by a dot: «3 серии, 960 МБ» reads as a
                // sentence about this title.
                Text(
                    "${pluralEpisodes(title.episodes.size)}, ${formatBytes(title.bytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = KaeruSecondary,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = KaeruSecondary,
            )
            IconAction(Icons.Default.Delete, "$REMOVE_TITLE: ${title.title}", onRemoveTitle)
        }
        if (expanded) {
            title.episodes.forEach { episode ->
                EpisodeRow(episode) { onRemove(episode.episode) }
            }
        }
    }
}

/**
 * One downloaded episode: which one it is, what it takes, and what the engine is doing with it.
 *
 * The state line is only there while there is one — a finished download says its size and nothing
 * else, because «Скачано» on a screen called «Загрузки» is a row explaining why it is on screen.
 */
@Composable
private fun EpisodeRow(download: EpisodeDownload, onRemove: () -> Unit) {
    val state = downloadStateLine(download)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KaeruTokens.MinTouchTarget)
            .padding(start = KaeruTokens.GutterPhone + PosterWidth + KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${download.episode} серия, ${formatBytes(download.bytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruText,
            )
            if (state != null) {
                Text(state, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
            }
        }
        IconAction(Icons.Default.Delete, "$REMOVE_EPISODE: ${download.episode} серия", onRemove)
    }
}

@Composable
private fun PolicySection(
    policy: DownloadPolicy,
    onQuality: (Quality?) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDeleteWatched: (Boolean) -> Unit,
    onLimit: (Long?) -> Unit,
) {
    SettingsSection(SETTINGS) {
        SettingLabel(QUALITY)
        SettingNote(QUALITY_NOTE)
        SettingChoiceRow(
            options = QUALITIES,
            label = ::downloadQualityLabel,
            selected = { it == policy.quality },
            onSelect = onQuality,
        )
        SettingSwitchRow(WIFI_ONLY, policy.wifiOnly, onWifiOnly)
        SettingSwitchRow(DELETE_WATCHED, policy.deleteWatched, onDeleteWatched)
        SettingNote(DELETE_WATCHED_NOTE)
        SettingLabel(LIMIT)
        SettingNote(LIMIT_NOTE)
        SettingChoiceRow(
            options = LIMITS,
            label = ::downloadLimitLabel,
            selected = { it == policy.limitBytes },
            onSelect = onLimit,
        )
    }
}

@Composable
private fun ClearAllDialog(bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    ConfirmRemovalDialog(CLEAR_TITLE, bytes, onDismiss, onConfirm)

/**
 * Asked before downloads go, whether it is one title or all of them.
 *
 * A title is hundreds of megabytes of somebody's connection and, on a train, the difference between
 * having something to watch and not — the same reason the player asks about a single episode. The
 * size is in the sentence because it is what a viewer clearing space is deciding on.
 */
@Composable
private fun ConfirmRemovalDialog(title: String, bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { DestructiveButton(CLEAR_CONFIRM, onClick = onConfirm) },
        dismissButton = { SecondaryButton(CLEAR_CANCEL, onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = { Text("Освободится ${formatBytes(bytes)}", style = MaterialTheme.typography.bodyMedium) },
        shape = KaeruTokens.CardShape,
        containerColor = KaeruSurface,
        // As everywhere else in this app: depth is the background ramp and the dialog's own scrim.
        tonalElevation = 0.dp,
        titleContentColor = KaeruText,
        textContentColor = KaeruSecondary,
    )
}

/** Which titles are open, across a rotation. A `Set<Int>` travels as the array a `Bundle` holds. */
private val TitlesSaver: Saver<Set<Int>, IntArray> =
    Saver(save = { it.toIntArray() }, restore = { it.toSet() })

