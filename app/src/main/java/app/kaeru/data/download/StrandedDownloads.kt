package app.kaeru.data.download

/**
 * Which downloads the network stopped, kept across restarts.
 *
 * media3 persists one bit about a failure — «unknown» — so the row itself cannot say whether it
 * died in a tunnel, on an expired signature or on a full disk. Only the exception knew, and it
 * exists for the instant the listener fires. [DownloadFailures] keeps that in memory for the copy
 * a screen shows; this keeps the one part of it that has to outlive the process.
 *
 * It has to, because of what it is for. A download that failed in a tunnel is the one kind worth
 * putting back in the queue when the network returns, and the tunnel is usually the last thing
 * that happens before the phone is put away and the process is killed. An eligibility that only
 * lived in memory meant the promise «Нет связи, загрузка продолжится позже» was kept for a viewer
 * who stayed in the app and broken for everyone else.
 *
 * Ids are [app.kaeru.domain.download.DownloadKey.id]s, which is what the engine indexes rows by.
 */
interface StrandedDownloads {

    /** Everything the network stopped and nothing has picked up since. */
    suspend fun stranded(): Set<String>

    suspend fun recordStranded(id: String)

    /** Back in the queue, finished, or gone: not stranded any more, whichever it was. */
    suspend fun forgetStranded(id: String)
}
