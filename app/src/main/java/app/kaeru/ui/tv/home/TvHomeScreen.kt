package app.kaeru.ui.tv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.discover.Season
import app.kaeru.ui.common.design.Backdrop
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SkeletonHero
import app.kaeru.ui.common.design.SkeletonRow
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.TvPosterCard
import app.kaeru.ui.common.design.seasonTitle
import app.kaeru.ui.common.home.DiscoverContent
import app.kaeru.ui.common.home.DiscoverRow
import app.kaeru.ui.common.home.DiscoverRows
import app.kaeru.ui.common.home.HomeContent
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.home.discoverRows
import app.kaeru.ui.common.home.homeContentState
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvFocusKey
import app.kaeru.ui.tv.TvFocusMemory
import app.kaeru.ui.tv.TvFocusRow
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.rememberTvFocusMemory
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvPreviewEmptyHome
import app.kaeru.ui.tv.tvPreviewHome
import app.kaeru.ui.tv.tvRestoreFocus
import java.time.Instant

private const val EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»"
private const val EMPTY_TEXT =
    "Отметьте аниме как «Смотрю» на Shikimori, и Kaeru продолжит с той серии, " +
        "на которой вы остановились."
private const val FIND_ANIME = "Найти аниме"
private const val SEASON_EMPTY = "В этом сезоне пока ничего нет"
private const val SEASON_FAILED = "Не удалось загрузить сезон"
private const val SYNC_FAILED_RETRY = "Обновить список"
private const val RETRY = "Повторить"

/** What each slot holds, so the list reuses a row's node for a row rather than for a heading. */
private const val ROW = "row"
private const val DISCOVER = "discover"
private const val NOTE = "note"

/** Big enough to read as a play control from across a room, small enough to sit on a line. */
private val PlayGlyph = 24.dp

/**
 * The television's home screen: artwork across the whole panel, one sentence about the title the
 * remote is sitting on, and the rows underneath it.
 *
 * Three rules hold the screen together, and each of them is a fault the previous version had:
 *
 * 1. **The hero never overlaps the rows.** It is a band of a fixed height at the top, and the rows
 *    start below it — not a block floating over a list that scrolls under it.
 * 2. **A focused card grows into space that is already there.** The rows carry
 *    [TvLayout.CardFocusPad] above and below precisely so the six per cent a card gains is drawn
 *    rather than shaved off by the lazy list's own clipping.
 * 3. **A card's caption is one line.** The hero above is already showing the focused title in
 *    full, so the card underneath does not need two — and one line is a height the row can be
 *    sized for.
 *
 * [focus] and [listState] are hoisted so the shell can hand back the same card and the same scroll
 * position after a title card has been open over this screen.
 */
@Composable
fun TvHomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onPlay: (animeId: Int, episode: Int) -> Unit,
    onDetails: (animeId: Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    focus: TvFocusMemory = rememberTvFocusMemory(),
) {
    // One clock per feed: «осталось 14 мин» and «завтра» are read against it, and a line that
    // rewrote itself on every recomposition would be a line nobody could finish reading.
    val now = remember(state.feed) { Instant.now() }
    val rows = remember(state.feed, state.watchedThreshold, now) {
        tvHomeRows(state.feed, state.watchedThreshold, now)
    }
    val catalogue = remember(state.discover) { state.discover?.let(::discoverRows) }
    val content = homeContentState(state)

    when (content) {
        HomeContent.Loading -> TvHomeLoading(modifier)
        is HomeContent.Error -> TvHomeError(content.message, onRefresh, modifier)
        else -> TvHomeFeed(
            rows = if (content is HomeContent.Feed) rows else emptyList(),
            catalogue = catalogue,
            syncError = state.errorMessage.takeIf { content is HomeContent.Feed },
            onRefresh = onRefresh,
            onPlay = onPlay,
            onDetails = onDetails,
            onSeason = onSeason,
            onRetrySeason = onRetrySeason,
            onSearch = onSearch,
            listState = listState,
            focus = focus,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvHomeFeed(
    rows: List<TvHomeRow>,
    catalogue: DiscoverRows?,
    syncError: String?,
    onRefresh: () -> Unit,
    onPlay: (Int, Int) -> Unit,
    onDetails: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
    onSearch: (() -> Unit)?,
    listState: LazyListState,
    focus: TvFocusMemory,
    modifier: Modifier = Modifier,
) {
    val discoverCards = remember(catalogue) { catalogue.tvCards() }
    val focusRows = remember(rows, discoverCards) {
        rows.map { TvFocusRow(it.title, it.items.map(TvHomeCard::animeId)) } +
            discoverCards.map { (title, cards) -> TvFocusRow(title, cards.map(TvHomeCard::animeId)) }
    }
    // Resolved once per set of rows, not per recomposition: a background refresh must never pull
    // focus back from wherever the viewer has navigated to since.
    val restore = remember(focusRows) { tvRestoreFocus(focus.key, focusRows) }
    val claimed = remember(focusRows) { mutableStateOf(false) }
    val initial = remember(restore, rows, discoverCards) { cardFor(restore, rows, discoverCards) }
    var hero by remember(initial) { mutableStateOf(initial?.hero) }
    // Focus is both what the hero reads and what the shell hands back after a title card: one
    // callback writes both, so the two can never point at different cards.
    val onFocused: (String, TvHomeCard) -> Unit = { row, card ->
        hero = card.hero
        focus.key = TvFocusKey(row, card.animeId)
    }

    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = hero?.backdropUrl,
            animationSpec = tween(KaeruTokens.DurationHero),
            label = "tvBackdrop",
        ) { url ->
            Backdrop(url, Modifier.fillMaxSize(), scrimBottom = true, scrimStart = true)
        }
        Column(Modifier.fillMaxSize()) {
            TvHeroBand(hero)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TvLayout.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
            ) {
                if (rows.isEmpty() && onSearch != null) {
                    item(key = "invitation") {
                        TvInvitation(onSearch, claimsFocus = restore == null)
                    }
                }
                rows.forEach { row ->
                    item(key = row.title, contentType = ROW) {
                        TvCardRow(row.title, row.items, restore, claimed, onFocused, onPlay, onDetails)
                    }
                }
                catalogue?.let {
                    tvDiscoverSections(it, discoverCards, restore, claimed, onFocused, onDetails, onSeason, onRetrySeason)
                }
                if (syncError != null) {
                    item(key = "sync-error", contentType = NOTE) { TvSyncError(syncError, onRefresh) }
                }
            }
        }
    }
}

/**
 * The band above the rows: the name of the title the remote is on, what OK does with it, and where
 * the viewer is in it.
 *
 * Bottom-aligned inside a fixed height, so a one-line name sits low and a two-line one grows
 * upwards into the artwork rather than downwards into the cards.
 */
@Composable
private fun TvHeroBand(hero: TvHero?) {
    Crossfade(
        targetState = hero,
        animationSpec = tween(KaeruTokens.DurationHero),
        label = "tvHeroText",
    ) { shown ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(TvLayout.HeroHeight)
                .padding(start = TvLayout.Gutter, end = TvLayout.GutterEnd, top = TvLayout.SafeVertical),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (shown != null) {
                Column {
                    Text(
                        shown.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = KaeruText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.padding(top = KaeruTokens.Space3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
                    ) {
                        shown.action?.let { label ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = KaeruAccent,
                                    modifier = Modifier.size(PlayGlyph),
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KaeruAccent,
                                    maxLines = 1,
                                )
                            }
                        }
                        shown.meta?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = KaeruSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvCardRow(
    title: String,
    cards: List<TvHomeCard>,
    restore: TvFocusKey?,
    claimed: MutableState<Boolean>,
    onFocused: (String, TvHomeCard) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onDetails: (Int) -> Unit,
    header: Boolean = true,
) {
    Column {
        if (header) RowHeader(title, gutter = TvLayout.Gutter)
        LazyRow(
            modifier = Modifier.padding(top = KaeruTokens.Space3),
            contentPadding = PaddingValues(
                start = TvLayout.Gutter,
                end = TvLayout.GutterEnd,
                top = TvLayout.CardFocusPad,
                bottom = TvLayout.CardFocusPad,
            ),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            items(cards, key = { it.animeId }) { card ->
                TvFeedCard(
                    card = card,
                    isRestoreTarget = restore?.row == title && restore.id == card.animeId,
                    claimed = claimed,
                    onFocused = { onFocused(title, card) },
                    onPlay = onPlay,
                    onDetails = onDetails,
                )
            }
        }
    }
}

/**
 * One card. A press plays; only a card with nothing to start — an episode still to come, or a
 * title out of the catalogue — opens the title card instead, as a long press always does.
 *
 * The card claims the restored focus itself rather than being told to from above: asking from the
 * screen can run before the lazy row has composed this card at all, and that request then fails
 * for good.
 */
@Composable
private fun TvFeedCard(
    card: TvHomeCard,
    isRestoreTarget: Boolean,
    claimed: MutableState<Boolean>,
    onFocused: () -> Unit,
    onPlay: (Int, Int) -> Unit,
    onDetails: (Int) -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(isRestoreTarget) {
        if (isRestoreTarget && !claimed.value) {
            claimed.value = true
            requester.requestFocusOrLog("card ${card.animeId} of the television home screen")
        }
    }
    TvPosterCard(
        posterUrl = card.posterUrl,
        title = card.title,
        onClick = {
            val episode = card.playEpisode
            if (episode != null) onPlay(card.animeId, episode) else onDetails(card.animeId)
        },
        onLongClick = { onDetails(card.animeId) },
        badge = card.badge,
        progress = card.progress,
        titleMaxLines = 1,
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

/**
 * The catalogue rows, drawn like the viewer's own but with no badge and no strip: clean artwork is
 * what separates «titles you are in the middle of» from «titles you have never opened» as the eye
 * goes down the screen, with no heading needed to say so.
 */
private fun LazyListScope.tvDiscoverSections(
    rows: DiscoverRows,
    cards: List<Pair<String, List<TvHomeCard>>>,
    restore: TvFocusKey?,
    claimed: MutableState<Boolean>,
    onFocused: (String, TvHomeCard) -> Unit,
    onDetails: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
) {
    rows.popularNow?.let { row ->
        item(key = "discover-now", contentType = DISCOVER) {
            Column(Modifier.padding(top = KaeruTokens.Space4)) {
                RowHeader(row.title, gutter = TvLayout.Gutter)
                TvDiscoverContent(row, cards, restore, claimed, onFocused, onDetails, onRetry = null)
            }
        }
    }
    rows.seasonal?.let { row ->
        item(key = "discover-season", contentType = DISCOVER) {
            Column(Modifier.padding(top = KaeruTokens.Space4)) {
                RowHeader(row.title, gutter = TvLayout.Gutter)
                TvSeasonChips(rows.seasons, rows.season, onSeason)
                TvDiscoverContent(row, cards, restore, claimed, onFocused, onDetails, onRetrySeason)
            }
        }
    }
}

@Composable
private fun TvDiscoverContent(
    row: DiscoverRow,
    cards: List<Pair<String, List<TvHomeCard>>>,
    restore: TvFocusKey?,
    claimed: MutableState<Boolean>,
    onFocused: (String, TvHomeCard) -> Unit,
    onDetails: (Int) -> Unit,
    onRetry: (() -> Unit)?,
) {
    when (row.content) {
        is DiscoverContent.Titles -> TvCardRow(
            title = row.title,
            cards = cards.firstOrNull { it.first == row.title }?.second.orEmpty(),
            restore = restore,
            claimed = claimed,
            onFocused = onFocused,
            onPlay = { _, _ -> },
            onDetails = onDetails,
            header = false,
        )
        DiscoverContent.Loading -> SkeletonRow(
            modifier = Modifier.padding(top = KaeruTokens.Space3),
            count = 5,
            gutter = TvLayout.Gutter,
            posterWidth = KaeruTokens.PosterWidthTv,
        )
        DiscoverContent.Empty -> TvNote(SEASON_EMPTY)
        DiscoverContent.Failed -> {
            TvNote(SEASON_FAILED)
            if (onRetry != null) {
                TextAction(RETRY, onRetry, Modifier.padding(start = TvLayout.Gutter - KaeruTokens.Space3))
            }
        }
    }
}

/** The season switcher: three chips reading left to right as a timeline, each one focusable. */
@Composable
private fun TvSeasonChips(seasons: List<Season>, selected: Season, onSelect: (Season) -> Unit) {
    Row(
        Modifier
            .padding(start = TvLayout.Gutter, top = KaeruTokens.Space2)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        seasons.forEach { season ->
            StatusPill(
                text = seasonTitle(season),
                selected = season == selected,
                onClick = { onSelect(season) },
                role = Role.RadioButton,
                trailing = {},
            )
        }
    }
}

/**
 * Nothing in the list yet, which is a moment to point somewhere rather than shrug.
 *
 * It claims D-pad focus only when there is no card anywhere to claim it — an account with an empty
 * list still has the catalogue rows underneath, and those are the better place for the remote to
 * start on a television, where nobody wants to type.
 */
@Composable
private fun TvInvitation(onSearch: () -> Unit, claimsFocus: Boolean) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(claimsFocus) {
        if (claimsFocus) requester.requestFocusOrLog("the invitation on the television home screen")
    }
    EmptyState(
        title = EMPTY_TITLE,
        text = EMPTY_TEXT,
        actionLabel = FIND_ANIME,
        onAction = onSearch,
        modifier = Modifier.focusRequester(requester),
    )
}

/** One quiet line where the cards would be: a row reporting on itself, not a screen with nothing on it. */
@Composable
private fun TvNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        modifier = Modifier.padding(horizontal = TvLayout.Gutter, vertical = KaeruTokens.Space3),
    )
}

/**
 * The list would not sync, over a feed that still works.
 *
 * At the very bottom rather than over the artwork: the rows above it are what the viewer opened
 * the app for, and a banner across the top of a television is a thing they would have to navigate
 * around every time. Down here it is reachable with the D-pad and in nobody's way.
 */
@Composable
private fun TvSyncError(message: String, onRefresh: () -> Unit) {
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        TvNote(message)
        TextAction(SYNC_FAILED_RETRY, onRefresh, Modifier.padding(start = TvLayout.Gutter - KaeruTokens.Space3))
    }
}

/** The shape of the screen before the feed arrives, so nothing moves when it does. */
@Composable
private fun TvHomeLoading(modifier: Modifier = Modifier) = Column(modifier.fillMaxSize()) {
    SkeletonHero(aspect = 16f / 3f)
    Spacer(Modifier.height(KaeruTokens.Space6))
    SkeletonRow(count = 5, gutter = TvLayout.Gutter, posterWidth = KaeruTokens.PosterWidthTv)
}

/**
 * Nothing to show, and a reason. The retry claims D-pad focus, because on a television a screen
 * with no focused control is a screen the remote cannot reach at all.
 */
@Composable
private fun TvHomeError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusOrLog("the retry on the television home screen") }
    ErrorState(
        message = message,
        onRetry = onRetry,
        modifier = modifier.fillMaxSize().focusRequester(requester),
    )
}

/**
 * The catalogue's cards, built once per answer rather than inside a list content lambda: mapping
 * allocates a card per title, and that lambda runs on every recomposition of the screen.
 */
private fun DiscoverRows?.tvCards(): List<Pair<String, List<TvHomeCard>>> {
    if (this == null) return emptyList()
    return listOfNotNull(popularNow, seasonal).mapNotNull { row ->
        (row.content as? DiscoverContent.Titles)?.let { row.title to tvDiscoverCards(it.cards) }
    }
}

/** The card the restored focus points at, so the hero is right before focus has actually landed. */
private fun cardFor(
    key: TvFocusKey?,
    rows: List<TvHomeRow>,
    discover: List<Pair<String, List<TvHomeCard>>>,
): TvHomeCard? {
    if (key == null) return null
    val inRows = rows.firstOrNull { it.title == key.row }?.items
    val inDiscover = discover.firstOrNull { it.first == key.row }?.second
    return (inRows ?: inDiscover)?.firstOrNull { it.animeId == key.id }
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvHomeScreenPreview() = KaeruTvTheme {
    TvHomeScreen(
        state = tvPreviewHome(),
        onRefresh = {},
        onPlay = { _, _ -> },
        onDetails = {},
        onSeason = {},
        onRetrySeason = {},
        onSearch = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvHomeLoadingPreview() = KaeruTvTheme { TvHomeLoading() }

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvHomeEmptyPreview() = KaeruTvTheme {
    TvHomeScreen(
        state = tvPreviewEmptyHome(),
        onRefresh = {},
        onPlay = { _, _ -> },
        onDetails = {},
        onSeason = {},
        onRetrySeason = {},
        onSearch = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvHomeErrorPreview() = KaeruTvTheme {
    TvHomeError("Нет соединения. Проверьте интернет и повторите", {})
}
