package app.kaeru.ui.mobile.downloads

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.downloads.DownloadedTitle
import app.kaeru.ui.common.downloads.DownloadsUiState
import app.kaeru.ui.common.theme.KaeruTheme
import java.time.Instant

private const val DARK = 0xFF0B0C10
private const val MB = 1024L * 1024
private const val GB = 1024L * 1024 * 1024

// The shows the app is actually for, with the sizes they actually have: a long Russian name that
// wraps under a poster, and one title in the middle of downloading so the state lines are visible.
// Posters are null on purpose, so the previews also show the missing-artwork state.
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DANDADAN = "Дандадан"

private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")

private fun download(
    animeId: Int,
    episode: Int,
    state: DownloadState = DownloadState.COMPLETED,
    bytes: Long = 320 * MB,
    progress: Float = 1f,
    failure: String? = null,
) = EpisodeDownload(
    key = DownloadKey(animeId, episode, translationId = 11, quality = Quality.P720),
    state = state,
    bytes = bytes,
    progress = progress,
    failure = failure,
    updatedAt = now,
)

private val frieren = DownloadedTitle(
    animeId = 1,
    title = FRIEREN,
    posterUrl = null,
    episodes = listOf(download(1, 7), download(1, 8), download(1, 9)),
    bytes = 960 * MB,
)

private val dandadan = DownloadedTitle(
    animeId = 2,
    title = DANDADAN,
    posterUrl = null,
    episodes = listOf(
        download(2, 3, DownloadState.DOWNLOADING, bytes = 140 * MB, progress = 0.42f),
        download(2, 4, DownloadState.QUEUED, bytes = 0, progress = 0f),
        download(2, 5, DownloadState.WAITING_FOR_WIFI, bytes = 0, progress = 0f),
        download(2, 6, DownloadState.FAILED, bytes = 12 * MB, progress = 0.03f, failure = "нет места"),
    ),
    bytes = 152 * MB,
)

/** A title Room has never heard of, named by its id until Shikimori answers. */
private val unknown = DownloadedTitle(
    animeId = 404,
    title = "Тайтл №404",
    posterUrl = null,
    episodes = listOf(download(404, 1, bytes = 280 * MB)),
    bytes = 280 * MB,
)

private val titles = listOf(dandadan, frieren, unknown)

@Composable
private fun Downloads(state: DownloadsUiState) = KaeruTheme {
    DownloadsScreen(
        state = state,
        onBack = {},
        onRemove = { _, _ -> },
        onRemoveTitle = {},
        onRemoveAll = {},
        onQuality = {},
        onWifiOnly = {},
        onDeleteWatched = {},
        onLimit = {},
    )
}

/** Three titles, one of them mid-download, inside a limit that still has room. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1000)
@Composable
private fun DownloadsFilledPreview() = Downloads(
    DownloadsUiState(usedBytes = 3 * GB + 200 * MB, limitBytes = 5 * GB, titles = titles, loading = false),
)

/** Nothing downloaded: the settings stay, because they are how the first download is decided. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 900)
@Composable
private fun DownloadsEmptyPreview() = Downloads(DownloadsUiState(usedBytes = 0, loading = false))

/** Past the limit: the strip is red and full, and nothing was deleted to make it so. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 1000)
@Composable
private fun DownloadsOverLimitPreview() = Downloads(
    DownloadsUiState(
        usedBytes = 5 * GB + 400 * MB,
        limitBytes = 5 * GB,
        titles = titles,
        policy = DownloadPolicy.DEFAULT.copy(deleteWatched = true),
        loading = false,
    ),
)

/** No limit at all: a total with no proportion to draw under it. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 900)
@Composable
private fun DownloadsNoLimitPreview() = Downloads(
    DownloadsUiState(
        usedBytes = 7 * GB,
        limitBytes = null,
        titles = listOf(frieren),
        policy = DownloadPolicy.DEFAULT.copy(limitBytes = null),
        loading = false,
    ),
)
