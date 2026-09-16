package app.kaeru.ui.common.downloads

import app.kaeru.domain.download.DownloadState

/**
 * What a control says about where an episode is, on any surface that draws one.
 *
 * Seven engine states, four things worth saying. The grouping is the point: a queue waiting its
 * turn, a link being resolved and a download waiting for Wi-Fi are three different facts about the
 * engine and one fact to a viewer — «скоро будет» — and the difference between them belongs on the
 * «Загрузки» screen, where it can be a sentence.
 *
 * Shared because two surfaces drew this and disagreed. The season grid gave an episode waiting for
 * Wi-Fi the pending arrow while the player's top bar gave the same episode the done mark and
 * offered to delete it. Two mappings of one enum will always drift; there is one now, and each
 * surface only chooses its own glyph for it.
 */
enum class DownloadMark {
    /** Asked for, not here yet: queued, resolving, or waiting for a network it is allowed to use. */
    PENDING,

    /** Coming down now, and the fraction on the row is worth drawing. */
    RUNNING,

    /** On the device and playable with no network at all. */
    DONE,

    /** The engine gave up. Worth offering again rather than worth deleting. */
    FAILED,

    /**
     * On its way out, and nothing to ask of it until it has gone.
     *
     * Its own reading rather than [NONE], because the two differ in what a control may do. Nothing
     * is drawn for either — a tile that flashed «удаляем» for the half-second media3 takes would
     * be noise — but a control that offers «Скачать серию» over [NONE] must not offer it here: the
     * id it would enqueue is the one the engine is busy removing.
     */
    REMOVING,

    /** Nothing to say: never asked for. */
    NONE,
}

/** The one mapping, so no two surfaces can describe one episode differently. */
fun downloadMark(state: DownloadState?): DownloadMark = when (state) {
    null -> DownloadMark.NONE
    DownloadState.REMOVING -> DownloadMark.REMOVING
    DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.WAITING_FOR_WIFI -> DownloadMark.PENDING
    DownloadState.DOWNLOADING -> DownloadMark.RUNNING
    DownloadState.COMPLETED -> DownloadMark.DONE
    DownloadState.FAILED -> DownloadMark.FAILED
}
