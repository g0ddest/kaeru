package app.kaeru.domain.download

import java.time.Instant

/**
 * Where one download stands, as a screen has to draw it.
 *
 * [RESOLVING] has no counterpart in the download engine: it covers the seconds between the
 * viewer pressing «Скачать» and a signed link existing to hand over. Without it the grid cell
 * would sit unchanged through a network round trip and read as a press that did nothing.
 *
 * [WAITING_FOR_WIFI] is the engine's queued state seen through the policy: the same row, but the
 * reason it is not moving is a requirement the device does not meet rather than a queue ahead
 * of it.
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    WAITING_FOR_WIFI,
    RESOLVING,
    FAILED,
    COMPLETED,
    REMOVING,
}

/**
 * One episode on its way onto the device.
 *
 * [failure] is copy for the viewer, already in their language, or null while nothing has gone
 * wrong. The string is built in the data layer, which is where the failure is understood; this
 * type only carries it.
 */
data class EpisodeDownload(
    val key: DownloadKey,
    val state: DownloadState,
    val bytes: Long,
    val progress: Float,
    val failure: String?,
    val updatedAt: Instant,
) {
    val animeId: Int get() = key.animeId
    val episode: Int get() = key.episode
}
