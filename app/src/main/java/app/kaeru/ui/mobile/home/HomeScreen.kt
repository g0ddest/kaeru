package app.kaeru.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.kaeru.domain.discover.Season
import app.kaeru.domain.model.FeedItem
import app.kaeru.ui.common.design.EmptyState
import app.kaeru.ui.common.design.ErrorState
import app.kaeru.ui.common.design.HeroBanner
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.OfflineStrip
import app.kaeru.ui.common.design.PosterCard
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SkeletonHero
import app.kaeru.ui.common.design.SkeletonRow
import app.kaeru.ui.common.design.SyncingNotice
import app.kaeru.ui.common.design.episodeLine
import app.kaeru.ui.common.design.primaryAction
import app.kaeru.ui.common.home.DiscoverRows
import app.kaeru.ui.common.home.HomeContent
import app.kaeru.ui.common.home.HomeRow
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.home.discoverRows
import app.kaeru.ui.common.home.homeContentState
import app.kaeru.ui.common.home.homeRows
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.mobile.KaeruSnackbarHost
import app.kaeru.ui.mobile.RetrySnackbar
import app.kaeru.ui.common.player.CastButton
import java.time.Instant

private const val WORDMARK = "Kaeru"
private const val SETTINGS = "Настройки"
private const val DETAILS = "Подробнее"
private const val EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»"
private const val EMPTY_TEXT =
    "Отметьте аниме как «Смотрю» на Shikimori или найдите его здесь. " +
        "Kaeru продолжит с той серии, на которой вы остановились."
private const val FIND_ANIME = "Найти аниме"

// What each slot of the feed holds, so the list reuses a row's node for a row rather than for the hero.
private const val HERO = "hero"
private const val BAR_SPACE = "bar"
private const val ROW = "row"
private const val INVITATION = "invitation"

/** The height of [KaeruTopBar], which floats over this screen instead of taking space in it. */
private val BarHeight = 56.dp

/** How far the feed travels before the bar has a ground of its own. */
private val ScrimDistance = 120.dp

/**
 * The home screen: what to watch next, and one press to start it.
 *
 * The hero is nearly half the screen because it is the answer; the rows under it are the rest of
 * the list, each one adding the single fact its row leaves open. The top bar floats over the
 * artwork with no container of its own and takes a ground only once a row scrolls under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onPlay: (animeId: Int, episode: Int) -> Unit,
    onAnime: (Int) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
) {
    val content = homeContentState(state)
    val snackbar = remember { SnackbarHostState() }
    // Over a feed the viewer can still use, a failed refresh is a snackbar; over an empty one it is
    // the screen, and two «Повторить» at once would be one too many.
    RetrySnackbar(state.errorMessage.takeIf { content is HomeContent.Feed }, snackbar, onRefresh)
    val listState = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    // One clock per feed. «осталось 14 мин» and «завтра» are read against it, and a line that
    // rewrote itself on every recomposition would be a line nobody could finish reading.
    val now = remember(state.feed) { Instant.now() }
    // Built here rather than inside a list content lambda: it allocates a card per title and
    // formats a line per card, and that lambda re-runs on every recomposition of the screen.
    val catalogue = remember(state.discover) { state.discover?.let(::discoverRows) }
    // Whether the content still has to leave room for the floating bar. Offline it does not: the
    // strip above already reserved that height, and a second reservation would be a hole.
    val barSpace = !state.offline
    // The first sync says what it is doing in words and an amber strip. Material pins its spinner
    // open for the whole of any refresh, gesture or not, so on a first launch the screen would say
    // the same thing twice in two vocabularies. The gesture stays armed; only its indicator waits.
    val spinning = state.isRefreshing && content != HomeContent.FirstSync
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // With no network the strip takes real space under the bar rather than floating over
            // the hero. It is one line the viewer has to be able to read, and the bar is the one
            // thing on this screen that is allowed to sit on top of artwork.
            if (state.offline) {
                Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(BarHeight))
                OfflineStrip()
            }
            PullToRefreshBox(
                isRefreshing = spinning,
                onRefresh = onRefresh,
                state = pull,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pull,
                        isRefreshing = spinning,
                        // The inset belongs to whatever is at the top of the window. Offline the
                        // strip is, and the spinner is already below it.
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .then(
                                if (barSpace) {
                                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                                } else {
                                    Modifier
                                },
                            ),
                        containerColor = KaeruElevated,
                        color = KaeruAccent,
                    )
                },
            ) {
                when (content) {
                    HomeContent.Loading -> HomeLoading()
                    HomeContent.FirstSync -> HomeFirstSync(barSpace)
                    is HomeContent.Error -> HomeError(content.message, onRefresh)
                    HomeContent.Empty ->
                        HomeEmpty(onSearch, catalogue, onAnime, onSeason, onRetrySeason, barSpace)
                    HomeContent.Feed -> FeedList(
                        state, catalogue, now, listState, onPlay, onAnime, onSeason, onRetrySeason, barSpace,
                    )
                }
            }
        }
        HomeBar(listState, onSettings)
        KaeruSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(KaeruTokens.Space4))
    }
}

/** The wordmark and the two things that are not content: casting, and where the settings are. */
@Composable
private fun HomeBar(listState: LazyListState, onSettings: () -> Unit) {
    val distance = with(LocalDensity.current) { ScrimDistance.toPx() }
    // Read in the draw phase: the bar's ground follows the scroll without recomposing anything.
    val scrim = {
        if (listState.firstVisibleItemIndex > 0) {
            1f
        } else {
            (listState.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
        }
    }
    KaeruTopBar(
        title = WORDMARK,
        modifier = Modifier.drawBehind { drawRect(KaeruBackground, alpha = scrim()) },
        transparent = true,
        actions = {
            CastButton(Modifier.padding(horizontal = KaeruTokens.Space1), overArtwork = true)
            IconAction(Icons.Default.Settings, SETTINGS, onSettings, overArtwork = true)
        },
    )
}

@Composable
private fun FeedList(
    state: HomeUiState,
    catalogue: DiscoverRows?,
    now: Instant,
    listState: LazyListState,
    onPlay: (Int, Int) -> Unit,
    onAnime: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
    barSpace: Boolean,
) {
    // `now` is remembered on the same feed, so keying on it as well would buy nothing.
    val rows = remember(state.feed, state.watchedThreshold) {
        homeRows(state.feed, state.watchedThreshold, now)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = KaeruTokens.Space8),
    ) {
        val top = state.feed.top
        if (top != null) {
            item(key = "hero", contentType = HERO) { Hero(top, state.watchedThreshold, now, onPlay, onAnime) }
        } else if (barSpace) {
            // Nothing for the floating bar to float over, so the first row starts below it.
            item(key = "bar", contentType = BAR_SPACE) {
                Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(BarHeight))
            }
        }
        rows.forEach { row ->
            item(key = row.title, contentType = ROW) { FeedRow(row, onAnime) }
        }
        // Below everything the viewer already owns: what everyone else is watching.
        catalogue?.let { discoverSections(it, onAnime, onSeason, onRetrySeason) }
    }
}

@Composable
private fun Hero(
    item: FeedItem,
    threshold: Float,
    now: Instant,
    onPlay: (Int, Int) -> Unit,
    onAnime: (Int) -> Unit,
) {
    val anime = item.entry.anime
    // Routed through the same decision the title screen uses, so a hero and a title screen can
    // never name different episodes — and both halves of that decision are used. The feed raises
    // only playable items to the top today, which makes `enabled` look like a formality; it is one
    // feed rule away from not being, and a hero that ignored it would offer an episode nobody can
    // play.
    val action = primaryAction(item.entry, threshold, now)
    HeroBanner(
        title = anime.title,
        statusLine = episodeLine(item, now),
        // A screenshot is the show in motion; the poster is the fallback, cropped to the same shape.
        backdropUrl = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
        primaryLabel = action.label,
        onPrimary = { onPlay(anime.id, action.episode ?: item.episode) },
        primaryEnabled = action.enabled,
        secondaryLabel = DETAILS,
        onSecondary = { onAnime(anime.id) },
    )
}

@Composable
private fun FeedRow(row: HomeRow, onAnime: (Int) -> Unit) {
    Column(Modifier.padding(top = KaeruTokens.Space6)) {
        RowHeader(row.title)
        LazyRow(
            modifier = Modifier.padding(top = KaeruTokens.Space3),
            contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            // Keyed on the card rather than on the title: «Скачано» can hold two episodes of one
            // anime, and two items under the same key is a crash.
            items(row.items, key = { it.key }) { card ->
                PosterCard(
                    posterUrl = card.posterUrl,
                    title = card.title,
                    onClick = { onAnime(card.animeId) },
                    subtitle = card.subtitle,
                    badge = card.badge,
                    progress = card.progress,
                )
            }
        }
    }
}

/** The shape of the screen before the feed arrives, so nothing moves when it does. */
@Composable
private fun HomeLoading() = Column(Modifier.fillMaxSize().clipToBounds()) {
    SkeletonHero()
    Spacer(Modifier.height(KaeruTokens.Space6))
    SkeletonRow()
    Spacer(Modifier.height(KaeruTokens.Space4))
    SkeletonRow()
}

/**
 * The same skeletons, with a sentence saying why they are still skeletons.
 *
 * [HomeLoading] is a database read and is gone within a frame; this is a whole list coming over the
 * network and lasts seconds, which is long enough for silence to become a question — and long
 * enough that the old screen had time to tell a viewer who had just signed in that their list was
 * empty.
 *
 * The sentence is above the hero rather than in the seam below it, which is the opposite of where
 * the television puts it. A phone's hero is four-fifths of the screen's width tall, so anything
 * under it is at or past the fold on a small device, and the only thing on this screen that says
 * anything would be the one thing a viewer had to scroll for. Above the bar's height it is the
 * first line read, and when the list lands it is the hero that takes its place.
 */
@Composable
private fun HomeFirstSync(barSpace: Boolean) = Column(Modifier.fillMaxSize().clipToBounds()) {
    SyncingNotice(
        Modifier
            .then(if (barSpace) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(
                top = if (barSpace) BarHeight else KaeruTokens.Space4,
                start = KaeruTokens.GutterPhone,
                end = KaeruTokens.GutterPhone,
            )
            .padding(bottom = KaeruTokens.Space6),
    )
    SkeletonHero()
    Spacer(Modifier.height(KaeruTokens.Space6))
    SkeletonRow()
    Spacer(Modifier.height(KaeruTokens.Space4))
    SkeletonRow()
}

/**
 * The list is empty because the load failed, not because there is nothing in it.
 *
 * [ErrorState] already carries the cause and «Повторить», so nothing is added here. It is a lazy
 * list with one screen-sized item for the same reason the empty state is: pull-to-refresh listens
 * through nested scroll, and this is a screen a viewer will pull at.
 */
@Composable
private fun HomeError(message: String, onRetry: () -> Unit) = LazyColumn(Modifier.fillMaxSize()) {
    item { ErrorState(message = message, onRetry = onRetry, modifier = Modifier.fillParentMaxSize()) }
}

/**
 * Nothing in the list yet, which is a moment to point somewhere rather than shrug.
 *
 * The invitation is the first block of a scrolling page, not the page: under it are «Популярно
 * сейчас» and «Популярное в сезоне», and a brand-new account — the viewer those rows are most for
 * — should meet them without scrolling past a screen-high placeholder first. That is why the
 * [EmptyState] is sized to its own content; `fillParentMaxSize` here would obey the letter of
 * having the rows and still put them below the fold.
 *
 * It stays a lazy list: pull-to-refresh listens through nested scroll, and an empty home is
 * exactly where a viewer pulls.
 */
@Composable
private fun HomeEmpty(
    onSearch: () -> Unit,
    catalogue: DiscoverRows?,
    onAnime: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
    barSpace: Boolean,
) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = KaeruTokens.Space8),
) {
    item(key = "invitation", contentType = INVITATION) {
        EmptyState(
            title = EMPTY_TITLE,
            text = EMPTY_TEXT,
            // Clear of the floating bar, which has no hero to float over on this screen — unless
            // the offline strip is already holding that space open.
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (barSpace) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = BarHeight)
                    } else {
                        Modifier
                    },
                ),
            actionLabel = FIND_ANIME,
            onAction = onSearch,
        )
    }
    catalogue?.let { discoverSections(it, onAnime, onSeason, onRetrySeason) }
}
