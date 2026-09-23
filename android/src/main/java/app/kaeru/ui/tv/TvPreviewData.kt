package app.kaeru.ui.tv

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
import java.time.Duration
import java.time.Instant

/**
 * The titles every television preview is drawn with.
 *
 * A preview has no network and no database, so without a fixture the `@Preview`s are empty boxes
 * and nobody can see across a room what the screen will look like. Real Russian names on purpose:
 * they are twice as long as the English ones and are what actually decides whether a hero fits in
 * two lines and a card's caption in one.
 */
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DANDADAN = "Дандадан"
private const val JUJUTSU = "Магическая битва"
private const val SHADOW = "Восхождение в тени"
private const val ONE_PUNCH = "Ванпанчмен"
private const val DEMON_SLAYER = "Клинок, рассекающий демонов"

/**
 * A name the hero's one line does not hold at `displaySmall` and does two sizes down. Shared with
 * the render test, which is what checks the hero says all of it rather than «…Тюремн…».
 */
internal const val TV_PREVIEW_LONG_NAME = "Приговорённый быть героем: Тюремные хроники"

internal val TV_PREVIEW_NOW: Instant = Instant.parse("2026-09-13T20:00:00Z")

internal fun tvPreviewAnime(
    id: Int,
    title: String,
    status: AnimeStatus = AnimeStatus.ONGOING,
    episodes: Int = 12,
    aired: Int = 8,
    nextAt: Instant? = null,
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
    year = 2026,
    studio = "Madhouse",
    description = "Эльфийка-волшебница, пережившая своих спутников, отправляется в путь, " +
        "чтобы понять, чем для неё были эти десять лет и что они значили для них.",
)

internal fun tvPreviewEntry(
    anime: Anime,
    watched: Int = 6,
    status: ListStatus = ListStatus.WATCHING,
    watch: WatchState? = null,
) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, TV_PREVIEW_NOW), watch)

internal fun tvPreviewFeed(): HomeFeed {
    val frieren = tvPreviewAnime(1, FRIEREN, aired = 7)
    val continuing = FeedItem(
        tvPreviewEntry(frieren, watched = 6, watch = WatchState(1, 7, 600_000, 1_440_000, null, null, TV_PREVIEW_NOW)),
        episode = 7,
        kind = FeedKind.CONTINUE,
    )
    // A second card in «Продолжить», with the name that does not fit anywhere at full size.
    val prison = FeedItem(
        tvPreviewEntry(
            tvPreviewAnime(7, TV_PREVIEW_LONG_NAME, aired = 9),
            watched = 8,
            watch = WatchState(7, 9, 300_000, 1_440_000, null, null, TV_PREVIEW_NOW),
        ),
        episode = 9,
        kind = FeedKind.CONTINUE,
    )
    val fresh = listOf(
        FeedItem(tvPreviewEntry(tvPreviewAnime(2, DANDADAN, aired = 3), watched = 2), 3, FeedKind.NEW_EPISODE),
        FeedItem(tvPreviewEntry(tvPreviewAnime(3, JUJUTSU, aired = 8), watched = 7), 8, FeedKind.NEW_EPISODE),
        FeedItem(tvPreviewEntry(tvPreviewAnime(4, SHADOW, aired = 5), watched = 4), 5, FeedKind.NEW_EPISODE),
    )
    val upcoming = listOf(
        FeedItem(
            tvPreviewEntry(tvPreviewAnime(5, ONE_PUNCH, aired = 4, nextAt = TV_PREVIEW_NOW.plus(Duration.ofDays(1))), watched = 4),
            episode = 5,
            kind = FeedKind.UPCOMING,
        ),
    )
    val planned = listOf(
        FeedItem(
            tvPreviewEntry(tvPreviewAnime(6, DEMON_SLAYER, AnimeStatus.RELEASED, 26, 26), watched = 0, status = ListStatus.PLANNED),
            episode = 1,
            kind = FeedKind.PLANNED,
        ),
    )
    return HomeFeed(continuing, listOf(continuing, prison), fresh, emptyList(), upcoming, planned)
}

private fun tvPreviewDiscover() = DiscoverUiState(
    season = Season(SeasonKind.FALL, 2026),
    popularNow = listOf(
        tvPreviewAnime(11, DANDADAN),
        tvPreviewAnime(12, JUJUTSU),
        tvPreviewAnime(13, ONE_PUNCH),
        tvPreviewAnime(14, SHADOW),
        tvPreviewAnime(15, DEMON_SLAYER, AnimeStatus.RELEASED, 26, 26),
    ),
    seasonal = listOf(tvPreviewAnime(16, FRIEREN), tvPreviewAnime(17, DANDADAN)),
    anySeasonLoaded = true,
)

internal fun tvPreviewHome() = HomeUiState(
    feed = tvPreviewFeed(),
    isLoading = false,
    discover = tvPreviewDiscover(),
)

internal fun tvPreviewEmptyHome() = HomeUiState(
    feed = HomeFeed.EMPTY,
    isLoading = false,
    discover = tvPreviewDiscover(),
)
