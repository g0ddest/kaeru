package app.kaeru.ui.mobile.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.DetailsUiState
import app.kaeru.ui.common.theme.KaeruTheme
import java.time.Duration
import java.time.Instant

private const val DARK = 0xFF0B0C10

// A show the app is actually for, with the numbers it actually has: a long Russian name that wraps,
// a season longer than what has aired, and a position two thirds into the episode in progress.
// Posters and screenshots are null on purpose, so the previews also show the missing-artwork state.
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val SHADOW = "Восхождение в тени"

private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")

private fun anime(
    id: Int,
    title: String,
    romaji: String,
    episodes: Int,
    aired: Int,
    status: AnimeStatus = AnimeStatus.ONGOING,
) = Anime(
    id = id,
    nameRu = title,
    nameRomaji = romaji,
    posterUrl = null,
    screenshotUrls = emptyList(),
    status = status,
    episodes = episodes,
    episodesAired = aired,
    nextEpisodeAt = null,
    score = 9.1,
    year = 2023,
    studio = "Madhouse",
    description = "Эльфийка Фрирен пережила своих спутников по отряду героя и только теперь, " +
        "спустя десятилетия, начинает понимать, чем для неё были эти несколько лет. " +
        "Она отправляется на север, чтобы дойти до места, где можно поговорить с мёртвыми, " +
        "и по дороге берёт в ученицы девочку, которой предстоит прожить обычную человеческую жизнь.",
)

private val frieren = anime(1, FRIEREN, "Sousou no Frieren", episodes = 28, aired = 24)

private val watching = LibraryEntry(
    frieren,
    UserRate(1, 1, ListStatus.WATCHING, 20, now),
    WatchState(1, 21, 860_000, 1_400_000, translationId = 11, kodikSeason = 1, updatedAt = now),
)

private val anilibria = RankedTranslation(
    Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 28),
    oftenChosen = false,
)

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 900)
@Composable
private fun DetailsInListPreview() = KaeruTheme {
    DetailsScreen(
        state = DetailsUiState(
            entry = watching,
            anime = frieren,
            refreshing = false,
            translations = listOf(anilibria),
        ),
        onBack = {},
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
        onMarkWatched = {},
    )
}

/** Everything watched, the next episode not out yet: the one case that must not offer a play. */
@Preview(showBackground = true, backgroundColor = DARK, heightDp = 900)
@Composable
private fun DetailsWaitingPreview() = KaeruTheme {
    val waiting = anime(1, FRIEREN, "Sousou no Frieren", episodes = 28, aired = 24)
        .copy(nextEpisodeAt = now.plus(Duration.ofDays(1)))
    DetailsScreen(
        state = DetailsUiState(
            entry = LibraryEntry(waiting, UserRate(1, 1, ListStatus.WATCHING, 24, now), null),
            anime = waiting,
            refreshing = false,
        ),
        onBack = {},
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
        onMarkWatched = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 900)
@Composable
private fun DetailsNotInListPreview() = KaeruTheme {
    DetailsScreen(
        state = DetailsUiState(
            anime = anime(2, SHADOW, "Kage no Jitsuryokusha ni Naritai!", 12, 12, AnimeStatus.RELEASED),
            refreshing = false,
        ),
        onBack = {},
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
        onMarkWatched = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 900)
@Composable
private fun DetailsLoadingPreview() = KaeruTheme {
    DetailsScreen(
        state = DetailsUiState(refreshing = true),
        onBack = {},
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
        onMarkWatched = {},
    )
}

@Preview(showBackground = true, backgroundColor = DARK, heightDp = 600)
@Composable
private fun DetailsErrorPreview() = KaeruTheme {
    DetailsScreen(
        state = DetailsUiState(
            refreshing = false,
            errorMessage = "Нет соединения. Проверьте интернет",
        ),
        onBack = {},
        onRetry = {},
        onStatus = {},
        onPlay = { _, _ -> },
        onLoadTranslations = {},
        onPickTranslation = {},
        onMarkWatched = {},
    )
}
