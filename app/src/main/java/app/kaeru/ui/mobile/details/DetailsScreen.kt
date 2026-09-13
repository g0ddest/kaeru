package app.kaeru.ui.mobile.details

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.SkeletonHero
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.details.episodeCells
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.mobile.KaeruSnackbarHost
import app.kaeru.ui.mobile.RetrySnackbar
import app.kaeru.ui.mobile.player.CastButton
import java.time.Instant
private const val BACK = "Назад"
private const val EPISODES = "Серии"
private const val DESCRIPTION = "Описание"
private const val PLAN_IT = "Добавить в планы"
private const val MORE = "Ещё"
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
    // One clock per anime. «9 серия выйдет завтра» is read against it, and a label that rewrote
    // itself on every recomposition would be a label nobody could finish reading.
    val now = remember(anime) { Instant.now() }
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
        Actions(anime, state, now, onStatus, onPlay, onLoadTranslations, onPickTranslation)
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
 * viewer arrived with. It is also the one control that can be unpressable: an episode that has not
 * aired is named and disabled rather than offered, because pressing it would only reach
 * «Серия ещё не появилась в Kodik».
 *
 * The controls under it are quiet on purpose: the list status and the dub are settings, and a
 * screen with three loud controls has none.
 */
@Composable
private fun Actions(
    anime: Anime,
    state: DetailsUiState,
    now: Instant,
    onStatus: (ListStatus) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onLoadTranslations: () -> Unit,
    onPickTranslation: (Translation) -> Unit,
) {
    val entry = state.entry
    val action = remember(anime, entry, state.watchedThreshold, now) {
        detailsAction(anime, entry, state.watchedThreshold, now)
    }
    Column(Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space4)) {
        PrimaryButton(
            text = action.label,
            onClick = { action.episode?.let { episode -> onPlay(anime.id, episode) } },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.PlayArrow,
            enabled = action.enabled,
        )
        FlowRow(
            Modifier.padding(top = KaeruTokens.Space3),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            if (entry == null) {
                SecondaryButton(PLAN_IT, { onStatus(ListStatus.PLANNED) }, enabled = !state.updatingStatus)
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
                { BusySpinner() }
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

/** A write is in flight, in the smallest form that still reads: inside the pill that started it. */
@Composable
private fun BusySpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(BusySize),
        color = KaeruText,
        strokeWidth = BusyStroke,
    )
}

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
    val saving = state.savingTranslation
    StatusPill(
        text = translationLabel(state.translations, currentId),
        selected = false,
        onClick = {
            open = true
            // The catalogue is asked the first time the sheet opens and not on every visit here.
            onLoadTranslations()
        },
        role = Role.DropdownList,
        // The same spinner the status pill shows while the list is being written: picking a dub is
        // a write too, and until now it was the one that gave no sign of itself.
        trailing = if (saving) {
            { BusySpinner() }
        } else {
            null
        },
    )
    if (open) {
        TranslationPickerSheet(
            translations = state.translations,
            currentId = currentId,
            loading = state.loadingTranslations,
            errorMessage = state.translationsError,
            // A sheet reopened while the last pick is still being written takes no taps, so the
            // same row cannot be sent twice.
            enabled = !saving,
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
            TextAction(
                if (expanded) COLLAPSE else MORE,
                { expanded = !expanded },
                Modifier.padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3),
            )
        }
    }
}

/** The shape of the screen before the anime arrives, so nothing jumps when it does. */
@Composable
private fun DetailsSkeleton() = Column(Modifier.fillMaxSize()) {
    // The hero carries its own group; wrapping it in a second one would start a second clock and
    // put the two halves of the screen out of phase.
    SkeletonHero(aspect = BACKDROP_ASPECT)
    SkeletonGroup {
        Column(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space6),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            Skeleton(Modifier.fillMaxWidth(0.85f).height(TitleBlock))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(LineBlock))
            Skeleton(Modifier.fillMaxWidth().height(KaeruTokens.ButtonHeight))
            repeat(SKELETON_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    repeat(SKELETON_COLUMNS) {
                        Skeleton(Modifier.weight(1f).height(SkeletonCellHeight))
                    }
                }
            }
        }
    }
}

private val TitleBlock = 32.dp
private val LineBlock = 16.dp
private val SkeletonCellHeight = 56.dp
private const val SKELETON_ROWS = 2
private const val SKELETON_COLUMNS = 5
