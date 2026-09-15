package app.kaeru.ui.common.downloads

import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload

/**
 * One title's worth of downloads, as the screen groups them.
 *
 * Named by [title] and [posterUrl] rather than by carrying an `Anime`, because a download outlives
 * the catalogue entry it came from: downloads belong to the device and survive a sign-out, and a
 * cache wipe can leave an episode on the phone whose title Room no longer holds. That title still
 * has to be listed and still has to be deletable, so it is listed under «Тайтл №404» until
 * Shikimori answers again.
 */
data class DownloadedTitle(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    /** Every download of this title, in episode order, in whatever state it is in. */
    val episodes: List<EpisodeDownload>,
    /** What these episodes take together, as the row under the title says it. */
    val bytes: Long,
)

/**
 * The «Загрузки» screen as values.
 *
 * [usedBytes] comes from the engine rather than from adding [titles] up, and the difference is not
 * academic: the engine counts bytes on disk, which includes a download half finished and a segment
 * two episodes share. A line that disagreed with the limit doing the refusing would be the worse
 * of the two numbers to show.
 */
data class DownloadsUiState(
    val usedBytes: Long = 0,
    /**
     * The limit the viewer set, or null for «без лимита».
     *
     * The same value as `policy.limitBytes`, named at the top level because the usage line is
     * about it and reaching two levels down for the one number a screen is built around reads as
     * if it were incidental.
     */
    val limitBytes: Long? = DownloadPolicy.DEFAULT.limitBytes,
    val titles: List<DownloadedTitle> = emptyList(),
    val policy: DownloadPolicy = DownloadPolicy.DEFAULT,
    /** True only until the first rows arrive. An empty screen is not a loading one. */
    val loading: Boolean = true,
) {
    /** Past the limit the viewer set. The usage line says so; nothing is deleted over it. */
    val overLimit: Boolean get() = limitBytes != null && usedBytes > limitBytes
}

/**
 * What one episode row says it is doing, or null when it is simply on the device.
 *
 * A finished download says nothing: its size is already on the row, and «Скачано» next to it would
 * be the row explaining why it is on a screen called «Загрузки».
 */
fun downloadStateLine(download: EpisodeDownload): String? = when (download.state) {
    DownloadState.COMPLETED -> null
    DownloadState.QUEUED -> "В очереди"
    // The resolve is a Kodik round trip the viewer never asked about by name, so it reads as the
    // queue it is part of rather than as a step of its own.
    DownloadState.RESOLVING -> "В очереди"
    DownloadState.DOWNLOADING -> "Загружается, ${(download.progress * 100).toInt()} %"
    DownloadState.WAITING_FOR_WIFI -> "Ждём Wi-Fi"
    DownloadState.REMOVING -> "Удаляем"
    // The cause is worth the words: «Ошибка» alone leaves the viewer pressing «Скачать» again
    // against a limit, a dead link or a full disk with no way to tell which.
    DownloadState.FAILED -> download.failure?.let { "Ошибка: $it" } ?: "Ошибка"
}
