package app.kaeru.ui.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.ui.common.library.LibrarySort
import app.kaeru.ui.common.library.LibraryUiState
import app.kaeru.ui.common.library.selectLibrary
import app.kaeru.ui.common.library.sortKeys
import app.kaeru.ui.common.theme.KaeruTheme
import java.time.Instant

private const val DARK = 0xFF0B0C10

// Real shows with the numbers they really have, so a Russian name that will wrap on a phone wraps
// here too. Posters are null on purpose: these also show the card before its artwork arrives.
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
    nextEpisodeAt = null,
    score = 9.1,
    year = 2024,
    studio = "Madhouse",
    description = null,
)

private fun entry(
    anime: Anime,
    watched: Int,
    status: ListStatus = ListStatus.WATCHING,
    minutesIn: Long? = null,
) = LibraryEntry(
    anime = anime,
    rate = UserRate(anime.id.toLong(), anime.id, status, watched, now.minusSeconds(anime.id * 3600L)),
    watch = minutesIn?.let {
        WatchState(anime.id, watched + 1, it * 60_000, 24 * 60_000, translationId = null, kodikSeason = null, updatedAt = now)
    },
)

private val watching = listOf(
    entry(anime(1, FRIEREN, episodes = 28, aired = 24), watched = 6, minutesIn = 14),
    entry(anime(2, DANDADAN, episodes = 12, aired = 9), watched = 8),
    entry(anime(3, JUJUTSU, episodes = 24, aired = 4), watched = 3),
    entry(anime(4, ONE_PUNCH, episodes = 12, aired = 12, status = AnimeStatus.RELEASED), watched = 2),
    entry(anime(5, SHADOW, episodes = 12, aired = 4), watched = 4, minutesIn = 3),
    entry(anime(6, DEMON_SLAYER, episodes = 26, aired = 26, status = AnimeStatus.RELEASED), watched = 11),
)

private val counts = mapOf(
    ListStatus.WATCHING to 12,
    ListStatus.PLANNED to 40,
    ListStatus.COMPLETED to 128,
    ListStatus.ON_HOLD to 3,
)

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun LibraryLoadedPreview() = KaeruTheme {
    LibraryScreen(
        state = LibraryUiState(items = watching, counts = counts, isLoading = false),
        onStatus = {},
        onSort = {},
        onAnime = {},
        onSearch = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun LibraryByTitlePreview() = KaeruTheme {
    LibraryScreen(
        state = LibraryUiState(
            items = selectLibrary(sortKeys(watching), ListStatus.WATCHING, LibrarySort.TITLE),
            counts = counts,
            sort = LibrarySort.TITLE,
            isLoading = false,
        ),
        onStatus = {},
        onSort = {},
        onAnime = {},
        onSearch = {},
    )
}

/** The tab a viewer fills on purpose, with nothing in it yet: the one place there is a button. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun LibraryEmptyPlannedPreview() = KaeruTheme {
    LibraryScreen(
        state = LibraryUiState(counts = counts, status = ListStatus.PLANNED, isLoading = false),
        onStatus = {},
        onSort = {},
        onAnime = {},
        onSearch = {},
    )
}

/** One that fills itself, so the invitation would be the wrong thing to offer. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun LibraryEmptyDroppedPreview() = KaeruTheme {
    LibraryScreen(
        state = LibraryUiState(counts = counts, status = ListStatus.DROPPED, isLoading = false),
        onStatus = {},
        onSort = {},
        onAnime = {},
        onSearch = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun LibraryLoadingPreview() = KaeruTheme {
    LibraryScreen(
        state = LibraryUiState(),
        onStatus = {},
        onSort = {},
        onAnime = {},
        onSearch = {},
    )
}
