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
import app.kaeru.ui.common.design.OFFLINE
import app.kaeru.ui.common.design.OfflineStrip
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SkeletonHero
import app.kaeru.ui.common.design.SkeletonRow
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.SyncingNotice
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
import app.kaeru.ui.tv.TvRestoreTarget
import app.kaeru.ui.tv.TvRowStates
import app.kaeru.ui.tv.claimFocus
import app.kaeru.ui.tv.rememberTvFocusMemory
import app.kaeru.ui.tv.requestFocusOrLog
import app.kaeru.ui.tv.tvPreviewEmptyHome
import app.kaeru.ui.tv.tvPreviewHome
import app.kaeru.ui.tv.tvRestoreTarget
import app.kaeru.ui.tv.tvRowScroll
import kotlinx.coroutines.delay
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

/** How long the backdrop waits for the D-pad to stop before it fetches a new full-panel image. */
private const val BackdropSettle = 250L

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
    rowStates: TvRowStates = remember { TvRowStates() },
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
        HomeContent.FirstSync -> TvHomeFirstSync(modifier)
        is HomeContent.Error -> TvHomeError(content.message, onRefresh, modifier)
        else -> TvHomeFeed(
            rows = if (content is HomeContent.Feed) rows else emptyList(),
            catalogue = catalogue,
            offline = state.offline,
            syncError = state.errorMessage.takeIf { content is HomeContent.Feed },
            onRefresh = onRefresh,
            onPlay = onPlay,
            onDetails = onDetails,
            onSeason = onSeason,
            onRetrySeason = onRetrySeason,
            onSearch = onSearch,
            listState = listState,
            rowStates = rowStates,
            focus = focus,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvHomeFeed(
    rows: List<TvHomeRow>,
    catalogue: DiscoverRows?,
    offline: Boolean,
    syncError: String?,
    onRefresh: () -> Unit,
    onPlay: (Int, Int) -> Unit,
    onDetails: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
    onSearch: (() -> Unit)?,
    listState: LazyListState,
    rowStates: TvRowStates,
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
    val restore = remember(focusRows) { tvRestoreTarget(focus.key, focusRows) }
    // Once per visit to the screen, not once per change to the rows: a background refresh landing
    // while the viewer is in the drawer must not pull focus back out of it.
    val claimed = remember { mutableStateOf(false) }
    val initial = remember(restore, rows, discoverCards) { cardFor(restore, rows, discoverCards) }
    var hero by remember(initial) { mutableStateOf(initial?.hero) }
    // The words follow the remote at once; the picture behind them waits for the press to settle.
    //
    // A backdrop is a full-panel image, and keying one directly to focus meant a 1080p fetch and
    // decode for every card a viewer scrubbed past — twenty of them in two seconds along a long
    // row, on the one path where the screen must not do any work. `BackdropSettle` is longer than
    // the gap between presses of a scrub and shorter than a deliberate one, so the picture changes
    // once, when the viewer has arrived. Re-keyed on each change, so each press restarts the wait.
    var backdrop by remember(initial) { mutableStateOf(initial?.hero?.backdropUrl) }
    LaunchedEffect(hero?.backdropUrl) {
        delay(BackdropSettle)
        backdrop = hero?.backdropUrl
    }

    // The remembered card is brought into existence before anything asks to focus it. A lazy row
    // composes about a screenful, so a card further along than that is not a node any request can
    // name — which left the whole screen with no D-pad focus at all.
    //
    // Only while nothing has focus yet. `restore` is rebuilt on every change to the feed and to the
    // catalogue, not only on the way back from a title card, so without the latch a season landing
    // mid-scrub would scroll the row out from under a remote that was being used. Once focus has
    // arrived there is nothing left for this to do.
    LaunchedEffect(restore, claimed.value) {
        val at = restore?.takeUnless { claimed.value } ?: return@LaunchedEffect
        // Which row, then how far along it. The vertical half matters when a card changes rows —
        // watching an episode moves one out of «Новые серии» and into «Продолжить» — because a row
        // off the top of the screen composes no cards at all. Only the feed's own rows: they are
        // the column's items one for one (the invitation exists only when there are none of them),
        // while the catalogue's two are wrapped in items that carry headers and chips as well, so
        // a card remembered there is left to the column's own restored position.
        rows.indexOfFirst { it.title == at.row }
            .takeIf { it >= 0 }
            ?.let { tvRowScroll(it, listState.firstVisibleItemIndex, TvLayout.ColumnViewport) }
            ?.let { listState.scrollToItem(it) }
        val row = rowStates.of(at.row)
        tvRowScroll(at.index, row.firstVisibleItemIndex, TvLayout.RowViewport)
            ?.let { row.scrollToItem(it) }
    }

    // Focus is both what the hero reads and what the shell hands back after a title card: one
    // callback writes both, so the two can never point at different cards. It also latches the
    // claim — on focus arriving, never on focus being asked for.
    val onFocused: (String, TvHomeCard) -> Unit = { row, card ->
        hero = card.hero
        focus.key = TvFocusKey(row, card.animeId)
        claimed.value = true
    }

    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = backdrop,
            animationSpec = tween(KaeruTokens.DurationHero),
            label = "tvBackdrop",
        ) { url ->
            Backdrop(url, Modifier.fillMaxSize(), scrimBottom = true, scrimStart = true)
        }
        Column(Modifier.fillMaxSize()) {
            // Above the hero band rather than over the artwork: the television has no downloads to
            // offer instead, so this is the whole of what the screen has to say about the network,
            // and it is said once, at the top, in three words.
            // The panel crops its own edges, and the band below is what usually carries that
            // inset; above it, the strip has to carry its own or «Нет сети» lands in the part of
            // the picture a television does not draw.
            if (offline) {
                OfflineStrip(
                    modifier = Modifier.padding(top = TvLayout.SafeVertical),
                    text = OFFLINE,
                    gutter = TvLayout.Gutter,
                )
            }
            TvHeroBand(hero)
            LazyColumn(
                state = listState,
                // The safe area is held outside the scrolling viewport rather than being content
                // padding inside it. A focused card asks to be brought into the viewport, and a
                // viewport that ran to the bottom of the panel brought it flush against the five
                // per cent a television crops — so the name under the card landed in the part of
                // the picture the panel does not draw.
                modifier = Modifier.weight(1f).padding(bottom = TvLayout.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
            ) {
                if (rows.isEmpty() && onSearch != null) {
                    item(key = "invitation") {
                        TvInvitation(onSearch, claimsFocus = restore == null)
                    }
                }
                rows.forEach { row ->
                    item(key = row.title, contentType = ROW) {
                        TvCardRow(
                            row.title, row.items, rowStates.of(row.title),
                            restore, claimed, onFocused, onPlay, onDetails,
                        )
                    }
                }
                catalogue?.let {
                    tvDiscoverSections(
                        it, discoverCards, rowStates, restore, claimed,
                        onFocused, onDetails, onSeason, onRetrySeason,
                    )
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
        // Fast, where the backdrop is slow. The picture has time to settle because it waits for
        // the press to settle; the words have to keep up with the remote, and a 400ms fade
        // restarted by each press of a scrub is a band that reads as a smear.
        animationSpec = tween(KaeruTokens.DurationFast),
        label = "tvHeroText",
    ) { shown ->
        Box(
            Modifier
                .fillMaxWidth()
                // The height is the band's content; the safe area sits on top of it rather than
                // inside it, so a two-line name grows into the artwork and not into the panel edge.
                .height(TvLayout.SafeVertical + TvLayout.HeroHeight)
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
                                    overflow = TextOverflow.Ellipsis,
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
    rowState: LazyListState,
    restore: TvRestoreTarget?,
    claimed: MutableState<Boolean>,
    onFocused: (String, TvHomeCard) -> Unit,
    onPlay: (Int, Int) -> Unit,
    onDetails: (Int) -> Unit,
    header: Boolean = true,
) {
    Column {
        if (header) RowHeader(title, gutter = TvLayout.Gutter)
        LazyRow(
            // Hoisted above the screen, because this row lives inside a lazy item of a lazy column
            // and both are destroyed when a title card replaces the screen. Without it the vertical
            // position came back and every row reopened at its first card.
            state = rowState,
            modifier = Modifier.padding(top = KaeruTokens.Space3),
            contentPadding = PaddingValues(
                start = TvLayout.Gutter,
                end = TvLayout.GutterEnd,
                top = TvLayout.CardFocusPad,
                bottom = TvLayout.CardFocusPad,
            ),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        ) {
            items(cards, key = { it.key }) { card ->
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
    // Keyed on the claim as well as on the target, so a request that lost the race against
    // placement is made again rather than latched as done. The claim itself is latched by focus
    // arriving — in `onFocused`, out of `onFocusChanged` — because latching on the asking is how a
    // screen ends up with no focus and no second chance at it.
    LaunchedEffect(isRestoreTarget, claimed.value) {
        if (isRestoreTarget && !claimed.value) {
            requester.claimFocus("card ${card.animeId} of the television home screen")
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
    rowStates: TvRowStates,
    restore: TvRestoreTarget?,
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
                TvDiscoverContent(row, cards, rowStates, restore, claimed, onFocused, onDetails, onRetry = null)
            }
        }
    }
    rows.seasonal?.let { row ->
        item(key = "discover-season", contentType = DISCOVER) {
            Column(Modifier.padding(top = KaeruTokens.Space4)) {
                RowHeader(row.title, gutter = TvLayout.Gutter)
                TvSeasonChips(rows.seasons, rows.season, onSeason)
                TvDiscoverContent(row, cards, rowStates, restore, claimed, onFocused, onDetails, onRetrySeason)
            }
        }
    }
}

@Composable
private fun TvDiscoverContent(
    row: DiscoverRow,
    cards: List<Pair<String, List<TvHomeCard>>>,
    rowStates: TvRowStates,
    restore: TvRestoreTarget?,
    claimed: MutableState<Boolean>,
    onFocused: (String, TvHomeCard) -> Unit,
    onDetails: (Int) -> Unit,
    onRetry: (() -> Unit)?,
) {
    when (row.content) {
        is DiscoverContent.Titles -> TvCardRow(
            title = row.title,
            cards = cards.firstOrNull { it.first == row.title }?.second.orEmpty(),
            rowState = rowStates.of(row.title),
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
 * The same skeletons, with a sentence saying why they are still skeletons.
 *
 * This is the screen a viewer meets on the evening they sign the television in, and it is the one
 * that used to tell them their list was empty while the list was still being fetched.
 *
 * The sentence sits under the hero band rather than above it, which is the opposite of where the
 * phone puts it, because the geometry is the opposite too: a 1080p panel is 540dp tall and the band
 * is 180 of them, so the seam is the middle of the screen — while above the band is the top
 * twenty-seven device-independent pixels a panel is allowed to crop.
 *
 * It is the one state of this screen with nothing focusable on it. There is nothing to press yet;
 * the rail is still a D-pad press to the left, and the rows claim the focus themselves the moment
 * they land.
 */
@Composable
private fun TvHomeFirstSync(modifier: Modifier = Modifier) = Column(modifier.fillMaxSize()) {
    SkeletonHero(aspect = 16f / 3f)
    Spacer(Modifier.height(KaeruTokens.Space6))
    SyncingNotice(
        Modifier.padding(start = TvLayout.Gutter, end = TvLayout.GutterEnd),
        stripWidth = KaeruTokens.PosterWidthTv,
    )
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
    target: TvRestoreTarget?,
    rows: List<TvHomeRow>,
    discover: List<Pair<String, List<TvHomeCard>>>,
): TvHomeCard? {
    if (target == null) return null
    val inRows = rows.firstOrNull { it.title == target.row }?.items
    val inDiscover = discover.firstOrNull { it.first == target.row }?.second
    return (inRows ?: inDiscover)?.firstOrNull { it.animeId == target.id }
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
private fun TvHomeFirstSyncPreview() = KaeruTvTheme { TvHomeFirstSync() }

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
    TvHomeError("Нет соединения. Проверьте интернет", {})
}
