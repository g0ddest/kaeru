package app.kaeru.ui.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.design.Backdrop
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.MetaChip
import app.kaeru.ui.common.design.PrimaryAction
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.kaeruFocus
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.design.statusLabel
import app.kaeru.ui.common.details.DetailsContent
import app.kaeru.ui.common.details.DetailsUiState
import app.kaeru.ui.common.details.detailsAction
import app.kaeru.ui.common.details.detailsContentState
import app.kaeru.ui.common.details.detailsMeta
import app.kaeru.ui.common.details.translationLabel
import app.kaeru.ui.common.details.watchedLine
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvDialog
import app.kaeru.ui.tv.TvEpisodeCell
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvEpisodeGrid
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry
import java.time.Instant

private const val EPISODES = "Серии"
private const val NO_EPISODES = "Список серий пока неизвестен"
private const val EXPAND = "Развернуть"
private const val COLLAPSE = "Свернуть"
private const val NOT_AIRED = "не вышла"
private const val WATCHED = "Просмотрено"
private const val DUB = "Озвучка"
private const val SUBTITLES = "Субтитры"
private const val OFTEN_CHOSEN = "часто выбираете"
private const val NO_TRACKS = "Источник не предложил ни одной озвучки для этого аниме"
private const val TRACKS_FAILED_RETRY = "Повторить"
private const val LIST_STATUS = "Список"
private const val ADD_TO_LIST = "Добавить в список"

/** Five tiles across the right column; six would put the numbers below reading size on a 1080p panel. */
private const val EPISODE_COLUMNS = 5
private val EpisodeTile = 68.dp
private val WatchedMark = 16.dp
private val PosterWidth = KaeruTokens.PosterWidthTv

/** One skeleton block standing in for a heading, a chip row or a paragraph. */
private val HeadingBlock = 32.dp
private val LineBlock = 22.dp

/** Long enough to be worth reading, short enough to leave room for the controls above it. */
private const val DESCRIPTION_LINES = 3

/**
 * The title, in two columns: what the show is and what to do about it on the left, the season
 * itself on the right.
 *
 * Focus opens on the watch button, so the one-press rule still holds from here; the grid is one
 * press of right away, for the viewer who wants an episode other than the next one. The left
 * column scrolls under focus, because a long description and a full row of controls do not both
 * fit on a 540dp panel and the alternative — clipping one of them — is the fault this screen was
 * rebuilt to fix.
 *
 * The artwork of the title sits behind the whole screen under the two scrims the design system
 * allows, which is what keeps a title card from being a grey box with a poster on it.
 */
@Composable
fun TvTitleScreen(
    state: DetailsUiState,
    onRetry: () -> Unit,
    onStatus: (ListStatus) -> Unit,
    onPlay: (animeId: Int, episode: Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = detailsContentState(state)) {
        DetailsContent.Loading -> TvTitleLoading(modifier)
        is DetailsContent.Error -> TvTitleError(content.message, onRetry, modifier)
        is DetailsContent.Ready -> TvTitleReady(
            anime = content.anime,
            state = state,
            onStatus = onStatus,
            onPlay = onPlay,
            onLoadTranslations = onLoadTranslations,
            onPickTranslation = onPickTranslation,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvTitleReady(
    anime: Anime,
    state: DetailsUiState,
    onStatus: (ListStatus) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember(anime) { Instant.now() }
    val cells = remember(anime, state.entry, state.watchedThreshold) {
        tvEpisodeGrid(anime, state.entry, state.watchedThreshold)
    }
    val action = remember(anime, state.entry, state.watchedThreshold, now) {
        detailsAction(anime, state.entry, state.watchedThreshold, now)
    }
    var openSheet by remember { mutableStateOf<TvTitleSheet?>(null) }
    val primary = remember { FocusRequester() }
    LaunchedEffect(anime.id) { primary.requestFocusOrLog("the watch button of the title screen") }

    Box(modifier.fillMaxSize()) {
        Backdrop(
            anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            Modifier.fillMaxSize(),
            scrimBottom = true,
            scrimStart = true,
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(
                    start = TvLayout.Gutter,
                    end = TvLayout.GutterEnd,
                    top = TvLayout.SafeVertical,
                    bottom = TvLayout.SafeVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
        ) {
            TvTitleDetails(
                anime = anime,
                state = state,
                action = action,
                primary = primary,
                onPlay = onPlay,
                onOpenSheet = { openSheet = it; if (it == TvTitleSheet.TRANSLATIONS) onLoadTranslations() },
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
            )
            TvEpisodeColumn(
                cells = cells,
                watched = watchedLine(state.entry?.rate?.episodes ?: 0, cells.size),
                onPlay = { episode -> onPlay(anime.id, episode) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }

    when (openSheet) {
        TvTitleSheet.STATUS -> TvStatusDialog(
            current = state.entry?.rate?.status,
            onPick = { onStatus(it); openSheet = null },
            onDismiss = { openSheet = null },
        )
        TvTitleSheet.TRANSLATIONS -> TvTranslationDialog(
            translations = state.translations,
            currentId = state.entry?.watch?.translationId,
            loading = state.loadingTranslations,
            errorMessage = state.translationsError,
            enabled = !state.savingTranslation,
            onRetry = onLoadTranslations,
            onPick = { onPickTranslation(it); openSheet = null },
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }
}

/** Which of the two panels is open over the screen. */
private enum class TvTitleSheet { STATUS, TRANSLATIONS }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TvTitleDetails(
    anime: Anime,
    state: DetailsUiState,
    action: PrimaryAction,
    primary: FocusRequester,
    onPlay: (Int, Int) -> Unit,
    onOpenSheet: (TvTitleSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(anime.id) { mutableStateOf(false) }
    val meta = remember(anime) { detailsMeta(anime) }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space6)) {
            TvPoster(anime)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = KaeruText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
                    verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
                ) {
                    meta.forEach { MetaChip(it) }
                }
            }
        }
        PrimaryButton(
            text = action.label,
            onClick = { action.episode?.let { onPlay(anime.id, it) } },
            icon = Icons.Default.PlayArrow,
            enabled = action.enabled,
            modifier = Modifier.focusRequester(primary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            StatusPill(
                text = state.entry?.rate?.status?.let(::statusLabel) ?: ADD_TO_LIST,
                selected = state.entry != null,
                onClick = { onOpenSheet(TvTitleSheet.STATUS) },
                role = Role.Button,
            )
            SecondaryButton(
                text = translationLabel(state.translations, state.entry?.watch?.translationId),
                onClick = { onOpenSheet(TvTitleSheet.TRANSLATIONS) },
            )
        }
        anime.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            TextAction(if (expanded) COLLAPSE else EXPAND, { expanded = !expanded })
        }
        state.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = KaeruText)
        }
    }
}

@Composable
private fun TvPoster(anime: Anime) =
    Poster(anime.posterUrl, anime.title, Modifier.width(PosterWidth).aspectRatio(KaeruTokens.PosterAspect))

/** The season on the right: every episode, and what is behind the viewer in each of them. */
@Composable
private fun TvEpisodeColumn(
    cells: List<TvEpisodeCell>,
    watched: String,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(EPISODES, style = MaterialTheme.typography.titleMedium, color = KaeruText)
            if (cells.isNotEmpty()) {
                Text(watched, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
            }
        }
        if (cells.isEmpty()) {
            Text(NO_EPISODES, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
            return@Column
        }
        val next = cells.indexOfFirst { !it.watched && it.aired }.coerceAtLeast(0)
        val grid = rememberLazyGridState()
        LaunchedEffect(cells) { grid.scrollToItem((next - EPISODE_COLUMNS).coerceAtLeast(0)) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(EPISODE_COLUMNS),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            // The four per cent a 68dp tile gains when focused, which the grid would otherwise clip.
            contentPadding = PaddingValues(KaeruTokens.Space1),
        ) {
            items(cells, key = { it.episode }) { cell ->
                TvEpisodeTile(cell, onPlay = { onPlay(cell.episode) })
            }
        }
    }
}

/**
 * One episode. An episode that has not aired cannot be pressed, which on a remote also means the
 * D-pad steps over it rather than landing somewhere nothing happens.
 */
@Composable
private fun TvEpisodeTile(cell: TvEpisodeCell, onPlay: () -> Unit) {
    Box(
        Modifier
            .height(EpisodeTile)
            .kaeruFocus(KaeruTokens.CardShape)
            .clip(KaeruTokens.CardShape)
            .background(if (cell.aired) KaeruElevated else KaeruElevated.copy(alpha = 0.45f))
            .then(
                if (cell.aired) {
                    Modifier.selectable(
                        selected = false,
                        role = Role.Button,
                        onClick = onPlay,
                    )
                } else {
                    // One spoken sentence instead of a number and a fragment read separately.
                    Modifier.clearAndSetSemantics {
                        contentDescription = "${cell.episode} серия, ещё не вышла"
                    }
                },
            ),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cell.episode.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (cell.aired) KaeruText else KaeruSecondary,
                maxLines = 1,
            )
            if (!cell.aired) {
                Text(NOT_AIRED, style = MaterialTheme.typography.labelSmall, color = KaeruSecondary, maxLines = 1)
            }
        }
        if (cell.watched) {
            Icon(
                Icons.Default.Check,
                contentDescription = WATCHED,
                tint = KaeruAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(KaeruTokens.Space1).size(WatchedMark),
            )
        }
        cell.progress?.let { fraction ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(KaeruTokens.ProgressHeight)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(KaeruAccent))
            }
        }
    }
}

/** Where this title sits in the viewer's list, changed from a panel rather than a menu. */
@Composable
private fun TvStatusDialog(current: ListStatus?, onPick: (ListStatus) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusOrLog("the list status panel") }
    TvDialog(title = LIST_STATUS, onDismiss = onDismiss) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
            items(ListStatus.entries, key = { it.name }) { status ->
                TvDialogRow(
                    title = statusLabel(status),
                    selected = status == current,
                    enabled = true,
                    onClick = { onPick(status) },
                    modifier = if (status == (current ?: ListStatus.WATCHING)) {
                        Modifier.focusRequester(first)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * Which voice this anime plays in.
 *
 * The list arrives ranked — the remembered track first, then the studios the viewer put at the top
 * of their settings, then the ones they keep choosing elsewhere — so the order is itself the
 * recommendation and the first row is almost always the right one.
 *
 * «Часто выбираете» is a second line rather than a chip here: a television row is read from three
 * metres away and a chip beside a studio name at that distance is a smudge, so the phrase joins
 * the caption it belongs to.
 */
@Composable
private fun TvTranslationDialog(
    translations: List<RankedTranslation>,
    currentId: Int?,
    loading: Boolean,
    errorMessage: String?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onPick: (Translation) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(translations, loading, errorMessage) {
        if (translations.isNotEmpty()) first.requestFocusOrLog("the dub panel")
    }
    TvDialog(title = DUB, onDismiss = onDismiss) {
        when {
            loading -> SkeletonGroup {
                Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4)) {
                    repeat(3) { Skeleton(Modifier.fillMaxWidth(0.7f).height(LineBlock)) }
                }
            }
            errorMessage != null -> {
                Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = KaeruText)
                SecondaryButton(TRACKS_FAILED_RETRY, onRetry, Modifier.padding(top = KaeruTokens.Space3))
            }
            translations.isEmpty() ->
                Text(NO_TRACKS, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                items(translations, key = { it.translation.id }) { ranked ->
                    TvDialogRow(
                        title = ranked.translation.title,
                        caption = trackCaption(ranked),
                        selected = ranked.translation.id == currentId,
                        enabled = enabled,
                        onClick = { onPick(ranked.translation) },
                        modifier = if (ranked == translations.first()) {
                            Modifier.focusRequester(first)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDialogRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .kaeruFocus(KaeruTokens.CardShape, focusedScale = 1f)
            .clip(KaeruTokens.CardShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = KaeruTokens.Space4, vertical = KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) KaeruText else KaeruSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary, maxLines = 1)
            }
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = KaeruAccent)
    }
}

/**
 * What kind of track this is, how much of the season it covers, and whether this viewer keeps
 * coming back to it — one caption, joined with commas rather than middle dots.
 */
private fun trackCaption(ranked: RankedTranslation): String {
    val track = ranked.translation
    val kind = if (track.type == TranslationKind.SUBTITLES) SUBTITLES else DUB
    val length = track.episodesCount?.takeIf { it > 0 }?.let(::pluralEpisodes)
    return listOfNotNull(kind, length, OFTEN_CHOSEN.takeIf { ranked.oftenChosen }).joinToString(", ")
}

/** The shape of the screen before the anime arrives, so nothing moves when it does. */
@Composable
private fun TvTitleLoading(modifier: Modifier = Modifier) = SkeletonGroup {
    Row(
        modifier
            .fillMaxSize()
            .padding(
                start = TvLayout.Gutter,
                end = TvLayout.GutterEnd,
                top = TvLayout.SafeVertical,
                bottom = TvLayout.SafeVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
    ) {
        Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space6)) {
                Skeleton(Modifier.width(PosterWidth).aspectRatio(KaeruTokens.PosterAspect))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                    Skeleton(Modifier.fillMaxWidth().height(HeadingBlock))
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(LineBlock))
                }
            }
            Skeleton(Modifier.fillMaxWidth(0.6f).height(KaeruTokens.ButtonHeight))
            Skeleton(Modifier.fillMaxWidth().height(HeadingBlock))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            Skeleton(Modifier.fillMaxWidth(0.3f).height(LineBlock))
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                    repeat(EPISODE_COLUMNS) { Skeleton(Modifier.weight(1f).height(EpisodeTile)) }
                }
            }
        }
    }
}

@Composable
private fun TvTitleError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusOrLog("the retry on the television title screen") }
    ErrorState(message, onRetry, modifier.fillMaxSize().focusRequester(requester))
}

private fun previewState(entry: LibraryEntry?, anime: Anime) = DetailsUiState(
    entry = entry,
    anime = anime,
    refreshing = false,
    watchedThreshold = 0.9f,
    translations = listOf(
        RankedTranslation(Translation(1, "AniLibria", TranslationKind.VOICE, 12), oftenChosen = true),
        RankedTranslation(Translation(2, "AniDub", TranslationKind.VOICE, 12), oftenChosen = false),
    ),
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleScreenPreview() = KaeruTvTheme {
    val anime = tvPreviewAnime(1, "Фрирен, провожающая в последний путь", aired = 7)
    TvTitleScreen(
        state = previewState(tvPreviewEntry(anime, watched = 5), anime),
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleLoadingPreview() = KaeruTvTheme { TvTitleLoading() }

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleErrorPreview() = KaeruTvTheme {
    TvTitleError("Нет соединения. Проверьте интернет и повторите", {})
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTranslationDialogPreview() = KaeruTvTheme {
    Box(Modifier.fillMaxSize()) {
        TvTranslationDialog(
            translations = previewState(null, tvPreviewAnime(1, "Дандадан")).translations,
            currentId = 2,
            loading = false,
            errorMessage = null,
            enabled = true,
            onRetry = {},
            onPick = {},
            onDismiss = {},
        )
    }
}
