package app.kaeru.ui.mobile.details

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.ui.common.design.Backdrop
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.MetaChip
import app.kaeru.ui.common.design.PosterImage
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.SkeletonHero
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.episodesLabel
import app.kaeru.ui.common.design.kaeruFocus
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.details.episodeCells
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.mobile.KaeruSnackbarHost
import app.kaeru.ui.mobile.RetrySnackbar
import app.kaeru.ui.mobile.player.CastButton
private const val BACK = "Назад"
private const val EPISODES = "Серии"
private const val DESCRIPTION = "Описание"
private const val PLAN_IT = "Добавить в планы"
private const val MORE = "Ещё"
private const val LESS = "Свернуть"
private const val CURRENT_STATUS = "Текущий статус"

/** The header artwork: wider than the hero, because here the poster and the title carry the screen. */
private const val BACKDROP_ASPECT = 16f / 10f

/** The poster, and how far it hangs past the artwork onto the page. */
private val PosterWidth = 96.dp
private val PosterHeight = 144.dp
private val PosterOverhang = 48.dp

/** How far the page travels before the floating bar takes a ground of its own. */
private val ScrimDistance = 160.dp

/** Where the description stops until the viewer asks for the rest. */
private const val COLLAPSED_LINES = 4

/**
 * One title: what to press, where you are in the season, and everything else quietly below.
 *
 * The artwork opens the screen and the poster breaks its bottom edge — the one deliberate overlap
 * in the app. Under it the order is the order of the questions a viewer actually has: what is this,
 * what happens if I press the big button, which episode am I on, and only then what is it about.
 */
@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStatus: (ListStatus) -> Unit,
    onPlay: (animeId: Int, episode: Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onMarkWatched: (episode: Int) -> Unit,
) {
    val content = detailsContentState(state)
    val snackbar = remember { SnackbarHostState() }
    // Over an anime the viewer can still read, a failure is a snackbar; with nothing to show it is
    // the screen, and two «Повторить» at once would be one too many.
    RetrySnackbar(state.errorMessage.takeIf { content is DetailsContent.Ready }, snackbar, onRetry)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().background(KaeruBackground)) {
        when (content) {
            DetailsContent.Loading -> DetailsSkeleton()
            is DetailsContent.Error -> ErrorState(content.message, onRetry, Modifier.fillMaxSize())
            is DetailsContent.Ready -> TitlePage(
                anime = content.anime,
                state = state,
                scroll = scroll,
                onStatus = onStatus,
                onPlay = onPlay,
                onLoadTranslations = onLoadTranslations,
                onPickTranslation = onPickTranslation,
                onMarkWatched = onMarkWatched,
            )
        }
        // Always drawn, whatever else is on the screen: a title that failed to load is never a
        // dead end. The disc under the glyphs is only worth it over artwork.
        DetailsBar(scroll, onBack, overArtwork = content is DetailsContent.Ready)
        KaeruSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(KaeruTokens.Space4))
    }
}

/** Back, and casting. No title: the name of the anime is 34sp two lines below it. */
@Composable
private fun DetailsBar(
    scroll: ScrollState,
    onBack: () -> Unit,
    overArtwork: Boolean,
) {
    val distance = with(LocalDensity.current) { ScrimDistance.toPx() }
    // Read in the draw phase: the bar's ground follows the scroll without recomposing anything.
    val scrim = { (scroll.value / distance).coerceIn(0f, 1f) }
    KaeruTopBar(
        title = null,
        modifier = Modifier.drawGround(scrim),
        transparent = true,
        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, BACK, onBack, overArtwork = overArtwork) },
        actions = { CastButton(Modifier.padding(horizontal = KaeruTokens.Space1), overArtwork = overArtwork) },
    )
}

/** The bar's ground, painted in the draw phase so the scroll never recomposes the bar. */
private fun Modifier.drawGround(alpha: () -> Float): Modifier =
    drawBehind { drawRect(KaeruBackground, alpha = alpha()) }

@Composable
private fun TitlePage(
    anime: Anime,
    state: DetailsUiState,
    scroll: ScrollState,
    onStatus: (ListStatus) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
    onMarkWatched: (Int) -> Unit,
) {
    val entry = state.entry
    val cells = remember(anime, entry, state.watchedThreshold) {
        episodeCells(anime, entry?.rate, entry?.watch, state.watchedThreshold)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = KaeruTokens.Space8),
    ) {
        Header(anime)
        // Room for the part of the poster that hangs past the artwork.
        Spacer(Modifier.height(PosterOverhang + KaeruTokens.Space4))
        Title(anime)
        MetaRow(anime)
        Actions(anime, state, onStatus, onPlay, onLoadTranslations, onPickTranslation)
        EpisodeSection(
            cells = cells,
            watched = entry?.rate?.episodes ?: 0,
            announced = anime.episodes,
            onPlay = { episode -> onPlay(anime.id, episode) },
            onMarkWatched = onMarkWatched,
        )
        anime.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
    }
}

/** A screenshot with the poster breaking its bottom edge. */
@Composable
private fun Header(anime: Anime) {
    Box(Modifier.fillMaxWidth()) {
        Backdrop(
            // A screenshot is the show in motion; the poster is the fallback, cropped to fit.
            url = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(BACKDROP_ASPECT),
        )
        // Decorative here, unlike `ui.common.Poster`: the name is set at 34sp directly underneath,
        // so describing the artwork as well would make a screen reader read the title twice.
        PosterImage(
            url = anime.posterUrl,
            title = anime.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = KaeruTokens.GutterPhone)
                .offset(y = PosterOverhang)
                .size(PosterWidth, PosterHeight),
        )
    }
}

@Composable
private fun Title(anime: Anime) {
    Column(Modifier.padding(horizontal = KaeruTokens.GutterPhone)) {
        Text(
            anime.title,
            style = MaterialTheme.typography.displaySmall,
            // The display size is a ceiling, not a fixed size: «Восхождение в тени» gets all 34sp
            // and «Фрирен, провожающая в последний путь» steps down until it fits three lines.
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 34.sp, stepSize = 1.sp),
            color = KaeruText,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        anime.nameRomaji.takeIf { it.isNotBlank() && it != anime.title }?.let { romaji ->
            Text(
                romaji,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = KaeruTokens.Space2),
            )
        }
    }
}

/**
 * The facts, one per chip.
 *
 * The gutter is inside the scroll rather than around it, so the last chip of a long row runs to
 * the edge of the screen and reads as something to push rather than something that got cut.
 */
@Composable
private fun MetaRow(anime: Anime) {
    val chips = remember(anime) { detailsMeta(anime) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KaeruTokens.GutterPhone)
            .padding(top = KaeruTokens.Space4),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        chips.forEach { MetaChip(it) }
    }
}

/**
 * What the screen is for, and the two things that change how it behaves.
 *
 * The amber button is the full width of the page because it is the answer to the question the
 * viewer arrived with. The controls under it are quiet on purpose: the list status and the dub are
 * settings, and a screen with three loud controls has none.
 */
@Composable
private fun Actions(
    anime: Anime,
    state: DetailsUiState,
    onStatus: (ListStatus) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
) {
    val entry = state.entry
    Column(Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space4)) {
        PrimaryButton(
            text = detailsActionLabel(entry, state.watchedThreshold),
            onClick = { onPlay(anime.id, entry?.nextEpisode(state.watchedThreshold) ?: 1) },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.PlayArrow,
        )
        FlowRow(
            Modifier.padding(top = KaeruTokens.Space3),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            if (entry == null) {
                SecondaryButton(PLAN_IT, { onStatus(ListStatus.PLANNED) })
            } else {
                StatusMenu(entry.rate.status, state.updatingStatus, onStatus)
                DubPill(state, entry, onLoadTranslations, onPickTranslation)
            }
        }
    }
}

/** Where this title sits in the list, and the six places it could sit instead. */
@Composable
private fun StatusMenu(current: ListStatus, busy: Boolean, onStatus: (ListStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StatusPill(
            text = statusLabel(current),
            // Not the selected look: amber here would be a second amber beside the watch button,
            // and this pill is a way into a menu rather than the thing the screen is for.
            selected = false,
            onClick = { if (!busy) open = true },
            role = Role.DropdownList,
            trailing = if (busy) {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(BusySize),
                        color = KaeruText,
                        strokeWidth = BusyStroke,
                    )
                }
            } else {
                null
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = KaeruTokens.CardShape,
            containerColor = KaeruElevated,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            ListStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = {
                        Text(
                            statusLabel(status),
                            style = MaterialTheme.typography.titleSmall,
                            color = KaeruText,
                        )
                    },
                    onClick = {
                        open = false
                        if (status != current) onStatus(status)
                    },
                    trailingIcon = {
                        if (status == current) {
                            Icon(Icons.Default.Check, contentDescription = CURRENT_STATUS, tint = KaeruAccent)
                        }
                    },
                    colors = MenuDefaults.itemColors(textColor = KaeruText, trailingIconColor = KaeruAccent),
                )
            }
        }
    }
}

private val BusySize = 16.dp
private val BusyStroke = 2.dp

/** Which voice this anime plays in, and the sheet that changes it. */
@Composable
private fun DubPill(
    state: DetailsUiState,
    entry: LibraryEntry,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentId = entry.watch?.translationId
    StatusPill(
        text = translationLabel(state.translations, currentId),
        selected = false,
        onClick = {
            open = true
            // The catalogue is asked the first time the sheet opens and not on every visit here.
            onLoadTranslations()
        },
        role = Role.DropdownList,
    )
    if (open) {
        TranslationPickerSheet(
            translations = state.translations,
            currentId = currentId,
            loading = state.loadingTranslations,
            errorMessage = state.translationsError,
            onRetry = onLoadTranslations,
            onPick = {
                open = false
                onPickTranslation(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** What the show is about, for whoever wants it, out of the way of whoever does not. */
@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var clipped by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        RowHeader(DESCRIPTION)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = KaeruSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            // Only a description that actually runs past four lines gets a control; a two-line
            // synopsis with «Ещё» under it is a button that does nothing.
            onTextLayout = { layout -> if (!expanded) clipped = layout.hasVisualOverflow },
            modifier = Modifier
                .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2)
                .animateContentSize(tween(KaeruTokens.DurationNormal)),
        )
        if (clipped) {
            QuietTextButton(if (expanded) LESS else MORE, { expanded = !expanded })
        }
    }
}

/** The shape of the screen before the anime arrives, so nothing jumps when it does. */
@Composable
private fun DetailsSkeleton() = SkeletonGroup {
    Column(Modifier.fillMaxSize()) {
        SkeletonHero(aspect = BACKDROP_ASPECT)
        Column(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space6),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            Skeleton(Modifier.fillMaxWidth(0.85f).height(TitleBlock))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(LineBlock))
            Skeleton(Modifier.fillMaxWidth().height(KaeruTokens.ButtonHeight))
            repeat(SKELETON_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    repeat(EPISODE_COLUMNS) {
                        Skeleton(Modifier.weight(1f).height(EpisodeCellHeight))
                    }
                }
            }
        }
    }
}

private val TitleBlock = 32.dp
private val LineBlock = 16.dp
private const val SKELETON_ROWS = 2

// --- the season -------------------------------------------------------------------------------

private const val EPISODE_COLUMNS = 5

/** Past this many, the grid is longer than the screen and is folded until asked for. */
private const val EPISODE_LIMIT = 60

private val EpisodeCellHeight = 56.dp
private val CheckSize = 14.dp

private const val SHOW_ALL = "Показать все"
private const val MARK_WATCHED = "Отметить просмотренной"
private const val WATCHED = "Просмотрено"
private const val NOT_AIRED = "не вышла"
private const val WATCH = "Смотреть"

/**
 * The season as a grid rather than a list.
 *
 * Twenty-eight numbers in six rows can be read at a glance — which ones are behind you, which one
 * you are in the middle of, where the season stops; twenty-eight list rows cannot. A very long
 * season folds at sixty, because past that the page below it stops being reachable.
 */
@Composable
private fun EpisodeSection(
    cells: List<EpisodeCell>,
    watched: Int,
    announced: Int,
    onPlay: (Int) -> Unit,
    onMarkWatched: (Int) -> Unit,
) {
    if (cells.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val folded = cells.size > EPISODE_LIMIT
    val shown = if (folded && !expanded) cells.take(EPISODE_LIMIT) else cells
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        RowHeader(EPISODES)
        Text(
            episodesLabel(watched, announced),
            style = MaterialTheme.typography.labelMedium,
            color = KaeruSecondary,
            modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space1),
        )
        Column(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            shown.chunked(EPISODE_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    row.forEach { cell ->
                        EpisodeTile(cell, Modifier.weight(1f), { onPlay(cell.number) }, { onMarkWatched(cell.number) })
                    }
                    // The last row keeps the pitch of the ones above it rather than stretching.
                    repeat(EPISODE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (folded) {
            QuietTextButton(if (expanded) LESS else SHOW_ALL, { expanded = !expanded })
        }
    }
}

/**
 * One episode, saying three different things three different ways.
 *
 * The check is Shikimori's count, the strip is where this device stopped, and a dimmed tile is an
 * episode that has not aired. A long press on one still to come does nothing — there is nothing
 * honest to mark — and neither does one already counted.
 */
@Composable
private fun EpisodeTile(
    cell: EpisodeCell,
    modifier: Modifier,
    onPlay: () -> Unit,
    onMarkWatched: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
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
                    onLongClick = if (cell.watched) null else ({ menu = true }),
                    onLongClickLabel = MARK_WATCHED,
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
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = KaeruTokens.CardShape,
            containerColor = KaeruElevated,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            DropdownMenuItem(
                text = { Text(MARK_WATCHED, style = MaterialTheme.typography.titleSmall) },
                onClick = {
                    menu = false
                    onMarkWatched()
                },
                colors = MenuDefaults.itemColors(textColor = KaeruText),
            )
        }
    }
}

/**
 * The quiet control that unfolds something: «Ещё», «Показать все».
 *
 * Secondary colour and title weight, never amber — it reveals text that is already on the page,
 * which is not the kind of thing the accent is for. The start padding lines its label up with the
 * gutter, past the button's own content padding.
 */
@Composable
private fun QuietTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3)
            .defaultMinSize(minHeight = KaeruTokens.MinTouchTarget)
            .kaeruFocus(KaeruTokens.ButtonShape),
        colors = ButtonDefaults.textButtonColors(contentColor = KaeruSecondary),
        contentPadding = PaddingValues(horizontal = KaeruTokens.Space3),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}
