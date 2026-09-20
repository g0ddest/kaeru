package app.kaeru.ui.mobile.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.ui.common.home.DiscoverUiState
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruTheme
import java.time.Duration
import java.time.Instant

private const val DARK = 0xFF0B0C10

// The shows the app is actually for, with the numbers those shows actually have, so a Russian name
// that will wrap on a real phone wraps here too. Posters are null on purpose: the previews then
// also show what a card looks like before its artwork arrives.
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DANDADAN = "Дандадан"
private const val JUJUTSU = "Магическая битва"
private const val ONE_PUNCH = "Ванпанчмен"
private const val SHADOW = "Восхождение в тени"
private const val DEMON_SLAYER = "Клинок, рассекающий демонов"

private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")

private fun anime(
    id: Int,
    title: String,
    episodes: Int,
    aired: Int,
    nextAt: Instant? = null,
    status: AnimeStatus = AnimeStatus.ONGOING,
) = Anime(
    id = id,
    nameRu = title,
    nameRomaji = title,
    posterUrl = null,
    screenshotUrls = emptyList(),
    status = status,
    episodes = episodes,
    episodesAired = aired,
    nextEpisodeAt = nextAt,
    score = 9.1,
    year = 2024,
    studio = "Madhouse",
    description = null,
)

private fun entry(anime: Anime, watched: Int, watch: WatchState? = null, status: ListStatus = ListStatus.WATCHING) =
    LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, now), watch)

private val frieren = entry(
    anime(1, FRIEREN, episodes = 28, aired = 24),
    watched = 6,
    watch = WatchState(1, 7, 860_000, 1_440_000, translationId = null, kodikSeason = null, updatedAt = now),
)
private val dandadan = entry(anime(2, DANDADAN, episodes = 12, aired = 9), watched = 8)
private val jujutsu = entry(anime(3, JUJUTSU, episodes = 24, aired = 4), watched = 3)
private val onePunch = entry(anime(4, ONE_PUNCH, episodes = 12, aired = 12, status = AnimeStatus.RELEASED), watched = 2)
private val shadow = entry(anime(5, SHADOW, episodes = 12, aired = 4, nextAt = now.plus(Duration.ofDays(1))), watched = 4)
private val demonSlayer = entry(anime(6, DEMON_SLAYER, episodes = 26, aired = 26), watched = 0, status = ListStatus.PLANNED)

private val continuing = FeedItem(frieren, 7, FeedKind.CONTINUE)

private val loadedFeed = HomeFeed(
    top = continuing,
    continueWatching = listOf(continuing),
    newEpisodes = listOf(FeedItem(dandadan, 9, FeedKind.NEW_EPISODE), FeedItem(jujutsu, 4, FeedKind.NEW_EPISODE)),
    nextUp = listOf(FeedItem(onePunch, 3, FeedKind.NEXT_UP)),
    upcoming = listOf(FeedItem(shadow, 5, FeedKind.UPCOMING)),
    planned = listOf(FeedItem(demonSlayer, 1, FeedKind.PLANNED)),
)

/**
 * The screen with no network: the strip, «Скачано» first, and no catalogue under the rows.
 *
 * Two downloads of one title on purpose — that is the row that used to be impossible to key.
 */
private val offlineFeed = loadedFeed.copy(
    downloaded = listOf(
        FeedItem(frieren, 8, FeedKind.DOWNLOADED),
        FeedItem(frieren, 9, FeedKind.DOWNLOADED),
        FeedItem(dandadan, 9, FeedKind.DOWNLOADED),
    ),
)

private val plannedOnlyFeed = HomeFeed(
    top = null,
    continueWatching = emptyList(),
    newEpisodes = emptyList(),
    nextUp = emptyList(),
    upcoming = emptyList(),
    planned = listOf(FeedItem(demonSlayer, 1, FeedKind.PLANNED)),
)

private val summer = Season(SeasonKind.SUMMER, 2026)

// Nothing in the viewer's list, which is the point: the discovery rows are the only place on this
// screen where a title carries no badge and no progress strip.
private val popularNow = listOf(
    anime(11, "Гачиакута", episodes = 24, aired = 11),
    anime(12, "Поднятие уровня в одиночку", episodes = 12, aired = 5),
    anime(13, "Кайдзю №8", episodes = 12, aired = 9),
)

private val seasonal = listOf(
    anime(14, "Проводы в последний путь", episodes = 13, aired = 13, status = AnimeStatus.RELEASED),
    anime(15, "Тихая звезда", episodes = 12, aired = 0, status = AnimeStatus.ANONS),
)

private val discovered = DiscoverUiState(
    season = summer,
    popularNow = popularNow,
    seasonal = seasonal,
    anySeasonLoaded = true,
)

@Composable
private fun Home(state: HomeUiState) = KaeruTheme {
    HomeScreen(
        state = state,
        onRefresh = {},
        onPlay = { _, _ -> },
        onAnime = {},
        onSettings = {},
        onSearch = {},
        onSeason = {},
        onRetrySeason = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeLoadedPreview() = Home(HomeUiState(feed = loadedFeed, isLoading = false))

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeLoadingPreview() = Home(HomeUiState(isLoading = true))

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeEmptyPreview() = Home(HomeUiState(isLoading = false))

/** Nothing cached and nothing on the network: the reason, not an invitation to add titles. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeErrorPreview() = Home(
    HomeUiState(isLoading = false, errorMessage = "Нет соединения. Проверьте интернет"),
)

/** Only planned titles: no hero to float over, so the first row starts under the bar instead. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomePlannedOnlyPreview() = Home(HomeUiState(feed = plannedOnlyFeed, isLoading = false))

/** The whole screen the way a viewer with a list and a network actually sees it. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1600)
@Composable
private fun HomeWithDiscoveryPreview() = Home(
    HomeUiState(feed = loadedFeed, isLoading = false, discover = discovered),
)

/**
 * A brand-new account: the invitation, and directly under it the two rows it has nothing else to
 * offer instead. The invitation is sized to its own words, so the first posters are on screen.
 */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1000)
@Composable
private fun HomeEmptyWithDiscoveryPreview() = Home(
    HomeUiState(isLoading = false, discover = discovered),
)

/** No network: one line under the bar, the downloads first, and nothing that needs Shikimori. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1000)
@Composable
private fun HomeOfflinePreview() = Home(
    HomeUiState(feed = offlineFeed, isLoading = false, offline = true),
)

/** No network and nothing downloaded either: the strip still says what is going on. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeOfflineEmptyPreview() = Home(HomeUiState(isLoading = false, offline = true))

/** The catalogue rows before they arrive: the real headings and the switcher are up, the cards are not. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1200)
@Composable
private fun HomeDiscoveryLoadingPreview() = Home(
    HomeUiState(
        feed = plannedOnlyFeed,
        isLoading = false,
        discover = DiscoverUiState(season = summer, loadingNow = true, loadingSeasonal = true),
    ),
)

/** A season nobody has indexed yet: the switcher stays, so the viewer can press their way back. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 900)
@Composable
private fun HomeSeasonEmptyPreview() = Home(
    HomeUiState(
        feed = plannedOnlyFeed,
        isLoading = false,
        discover = DiscoverUiState(
            season = summer.next(),
            popularNow = popularNow,
            seasonal = emptyList(),
            anySeasonLoaded = true,
        ),
    ),
)

/** A season that would not load, with the one line that says so and the way to ask again. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 900)
@Composable
private fun HomeSeasonFailedPreview() = Home(
    HomeUiState(
        feed = plannedOnlyFeed,
        isLoading = false,
        discover = DiscoverUiState(
            season = summer.previous(),
            popularNow = popularNow,
            seasonal = null,
            anySeasonLoaded = true,
        ),
    ),
)

/** Shikimori was unreachable from the start: both rows are simply not there. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun HomeDiscoveryUnavailablePreview() = Home(
    HomeUiState(feed = plannedOnlyFeed, isLoading = false, discover = DiscoverUiState(season = summer)),
)
