package app.kaeru.ui.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.focus.onFocusChanged
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
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvDialog
import app.kaeru.ui.tv.TvEpisodeAction
import app.kaeru.ui.tv.TvEpisodeCell
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvEpisodeActions
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
private const val WATCH = "Смотреть"
private const val MARK_WATCHED = "Отметить просмотренной"
private const val MARK_UNWATCHED = "Отметить непросмотренной"
private const val MORE_ACTIONS = "Что сделать с серией"
private const val MORE = "Ещё"
private const val DUB = "Озвучка"
private const val SUBTITLES = "Субтитры"
private const val OFTEN_CHOSEN = "часто выбираете"
private const val NO_TRACKS = "Источник не предложил ни одной озвучки для этого аниме"
private const val TRACKS_FAILED_RETRY = "Повторить"
private const val RETRY = "Повторить"
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

/**
 * Long enough to be worth reading, short enough to leave room for the controls above it.
 *
 * Three lines of `bodyMedium` is 78dp of the 486 the left column has, and the column comes to 446
 * with them: `TvRenderBudgetTest` renders this screen with the longest name and description the
 * catalogue produces and asserts there is nothing left to scroll.
 */
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
    onMarkWatched: (episode: Int) -> Unit,
    onMarkUnwatched: (episode: Int) -> Unit,
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
            onRetry = onRetry,
            onLoadTranslations = onLoadTranslations,
            onPickTranslation = onPickTranslation,
            onMarkWatched = onMarkWatched,
            onMarkUnwatched = onMarkUnwatched,
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
    onRetry: () -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onMarkWatched: (Int) -> Unit,
    onMarkUnwatched: (Int) -> Unit,
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
                onRetry = onRetry,
                onOpenSheet = { openSheet = it; if (it == TvTitleSheet.TRANSLATIONS) onLoadTranslations() },
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
            )
            TvEpisodeColumn(
                cells = cells,
                animeId = anime.id,
                watched = watchedLine(state.entry?.rate?.episodes ?: 0, cells.size),
                onPlay = { episode -> onPlay(anime.id, episode) },
                onMarkWatched = onMarkWatched,
                onMarkUnwatched = onMarkUnwatched,
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

/**
 * Which cell «Ещё» is about: the one the D-pad is on, or was on last, and the next unwatched
 * episode before the viewer has touched the grid at all — which is the one the screen scrolled to
 * and the one the watch button offers.
 */
private fun focusedIndex(cells: List<TvEpisodeCell>, focused: Int?, next: Int): Int {
    val known = focused?.let { episode -> cells.indexOfFirst { it.episode == episode } } ?: -1
    return if (known >= 0) known else next
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TvTitleDetails(
    anime: Anime,
    state: DetailsUiState,
    action: PrimaryAction,
    primary: FocusRequester,
    onPlay: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onOpenSheet: (TvTitleSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(anime.id) { mutableStateOf(false) }
    val meta = remember(anime) { detailsMeta(anime) }
    val description = anime.description?.takeIf { it.isNotBlank() }
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
                    maxLines = 2,
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
        // One row of quiet controls, and «Развернуть» is one of them.
        //
        // It used to sit on its own line under the paragraph, where it cost 48dp and a gap on a
        // panel that had neither: the description was drawn through by the bottom of the screen
        // and the control itself was not drawn at all. Beside the two controls it already has
        // room next to, it costs nothing, the D-pad reaches it with one press of right from
        // «Озвучка», and the paragraph under it keeps the whole width of the column to read in.
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
            if (description != null) {
                TextAction(if (expanded) COLLAPSE else EXPAND, { expanded = !expanded })
            }
        }
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.errorMessage?.let {
            // The phone puts this failure in a snackbar with «Повторить». A television has no
            // snackbar, so the line carries its own way forward — and being focusable is also what
            // brings it into view, since the D-pad walking down this column is what scrolls it.
            Text(it, style = MaterialTheme.typography.bodyMedium, color = KaeruText)
            TextAction(RETRY, onRetry)
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
    animeId: Int,
    watched: String,
    onPlay: (Int) -> Unit,
    onMarkWatched: (Int) -> Unit,
    onMarkUnwatched: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which episode a long press of OK opened, remembered for the column rather than for each
    // tile: one panel is over the screen at a time.
    var openFor by remember(animeId) { mutableStateOf<TvEpisodeCell?>(null) }
    // The tile the D-pad is on, or was on last. What «Ещё» acts on, so the button in the header
    // means the episode the viewer is looking at rather than a fixed one.
    var focused by remember(animeId) { mutableStateOf<Int?>(null) }
    val next = cells.indexOfFirst { !it.watched && it.aired }.coerceAtLeast(0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(EPISODES, style = MaterialTheme.typography.titleMedium, color = KaeruText)
            if (cells.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(watched, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
                    // The same panel a long press of OK opens, behind a control that is plainly
                    // there. Holding OK is the quicker way in and the one the home screen already
                    // uses, but it is invisible and it is the remote's to honour — an action the
                    // viewer cannot see is an action some remotes do not have.
                    TextAction(MORE, { openFor = cells.getOrNull(focusedIndex(cells, focused, next)) })
                }
            }
        }
        if (cells.isEmpty()) {
            Text(NO_EPISODES, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
            return@Column
        }
        val grid = rememberLazyGridState()
        // Once per title, not once per change to the cells. A status write or a progress update
        // landing from Room rebuilds `cells`, and keying on those would snap a viewer who had
        // scrolled to episode 24 back to whichever tile is next unwatched.
        LaunchedEffect(animeId) { grid.scrollToItem((next - EPISODE_COLUMNS).coerceAtLeast(0)) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(EPISODE_COLUMNS),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            // The four per cent a 68dp tile gains when focused, which the grid would otherwise clip.
            contentPadding = PaddingValues(KaeruTokens.Space1),
        ) {
            items(cells, key = { it.episode }) { cell ->
                TvEpisodeTile(
                    cell,
                    onPlay = { onPlay(cell.episode) },
                    onLongPress = { openFor = cell },
                    onFocused = { focused = cell.episode },
                )
            }
        }
    }
    openFor?.let { cell ->
        TvEpisodeDialog(
            cell = cell,
            onPlay = { openFor = null; onPlay(cell.episode) },
            onMarkWatched = { openFor = null; onMarkWatched(cell.episode) },
            onMarkUnwatched = { openFor = null; onMarkUnwatched(cell.episode) },
            onDismiss = { openFor = null },
        )
    }
}

/**
 * What a long press of OK offers for one episode, as a panel.
 *
 * A panel rather than the phone's dropdown for the reason every other secondary choice on this
 * screen is one: a remote has to be able to walk into the choice and back out of it, and
 * [TvDialog] is what keeps focus inside. It carries both marks — the television has no snackbar,
 * so this panel is also where a mis-pressed un-mark is put back.
 */
@Composable
private fun TvEpisodeDialog(
    cell: TvEpisodeCell,
    onPlay: () -> Unit,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(cell.episode) { first.requestFocusOrLog("the episode panel") }
    TvDialog(title = "${cell.episode} серия", onDismiss = onDismiss) {
        tvEpisodeActions(cell).forEachIndexed { index, action ->
            TvDialogRow(
                title = when (action) {
                    TvEpisodeAction.WATCH -> WATCH
                    TvEpisodeAction.MARK_WATCHED -> MARK_WATCHED
                    TvEpisodeAction.MARK_UNWATCHED -> MARK_UNWATCHED
                },
                selected = false,
                enabled = true,
                onClick = when (action) {
                    TvEpisodeAction.WATCH -> onPlay
                    TvEpisodeAction.MARK_WATCHED -> onMarkWatched
                    TvEpisodeAction.MARK_UNWATCHED -> onMarkUnwatched
                },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                role = Role.Button,
                // The one row that takes something away, in the colour that says so.
                destructive = action == TvEpisodeAction.MARK_UNWATCHED,
            )
        }
    }
}

/**
 * One episode. An episode that has not aired cannot be pressed, which on a remote also means the
 * D-pad steps over it rather than landing somewhere nothing happens.
 *
 * OK plays it; holding OK opens what else can be done with it, the same gesture the home screen
 * already uses on a poster.
 */
@Composable
private fun TvEpisodeTile(
    cell: TvEpisodeCell,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit,
) {
    Box(
        Modifier
            .height(EpisodeTile)
            // Gaining focus only: the tile the D-pad left is still the one «Ещё» in the header is
            // about, because that is where the viewer was when they went looking for it.
            .onFocusChanged { if (it.isFocused) onFocused() }
            .kaeruFocus(KaeruTokens.CardShape)
            .clip(KaeruTokens.CardShape)
            .background(if (cell.aired) KaeruElevated else KaeruElevated.copy(alpha = 0.45f))
            .then(
                if (cell.aired) {
                    Modifier.combinedClickable(
                        onClick = onPlay,
                        onClickLabel = WATCH,
                        onLongClick = onLongPress,
                        onLongClickLabel = MORE_ACTIONS,
                        role = Role.Button,
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
                overflow = TextOverflow.Ellipsis,
            )
            if (!cell.aired) {
                Text(
                    NOT_AIRED,
                    style = MaterialTheme.typography.labelSmall,
                    color = KaeruSecondary,
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
    // Whatever the panel is showing, something in it takes the D-pad: the first track when there
    // are tracks, «Повторить» when the load failed. A dialog with no focusable content is a dialog
    // a remote cannot leave, so the loading and empty branches are the two that deliberately have
    // none — there back is the only way out, and back is what a viewer presses at them anyway.
    LaunchedEffect(translations, loading, errorMessage) {
        if (translations.isNotEmpty() || errorMessage != null) first.requestFocusOrLog("the dub panel")
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
                SecondaryButton(
                    TRACKS_FAILED_RETRY,
                    onRetry,
                    Modifier.padding(top = KaeruTokens.Space3).focusRequester(first),
                )
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
    /** A row that does something rather than choosing something; the two read differently aloud. */
    role: Role = Role.RadioButton,
    /** A row that takes something away, in red. */
    destructive: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .kaeruFocus(KaeruTokens.CardShape, focusedScale = 1f)
            .clip(KaeruTokens.CardShape)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = KaeruTokens.Space4, vertical = KaeruTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    !enabled -> KaeruSecondary
                    destructive -> KaeruError
                    else -> KaeruText
                },
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
        onMarkWatched = {},
        onMarkUnwatched = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleLoadingPreview() = KaeruTvTheme { TvTitleLoading() }

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvTitleErrorPreview() = KaeruTvTheme {
    TvTitleError("Нет соединения. Проверьте интернет", {})
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
