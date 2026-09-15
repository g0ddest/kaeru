package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import app.kaeru.di.ApplicationScope
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The download engine as the rest of the app sees it.
 *
 * Two things are joined here. One is media3's own store, read through [DownloadsSource] and
 * written through [DownloadCommands]. The other is a handful of placeholders this class keeps
 * itself, for the seconds between a viewer pressing «Скачать» and a signed link existing to hand
 * over: media3 has no state for «being resolved», and without one the grid cell would sit
 * unchanged through a network round trip and read as a press that did nothing.
 *
 * Nothing is cached beyond that. Every read goes to the engine's index, which is the one place a
 * download survives the process, so a screen reopened after a restart shows what is really there.
 */
@UnstableApi
@Singleton
class Media3DownloadRepository @Inject constructor(
    private val source: DownloadsSource,
    private val commands: DownloadCommands,
    private val resolve: ResolveEpisodeStream,
    private val settings: SettingsStore,
    private val library: Provider<LibraryRepository>,
    private val failures: DownloadFailures,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationScope private val scope: CoroutineScope,
) : DownloadRepository {

    /** Episodes whose link is being resolved, keyed by anime and episode — no more than a few. */
    private val resolving = MutableStateFlow<Map<String, EpisodeDownload>>(emptyMap())

    // ---- reading the engine ------------------------------------------------------------------

    private class Snapshot(val rows: List<Download>, notMetRequirements: Int, val failureOf: (String) -> String) {
        val downloads: List<EpisodeDownload> =
            rows.mapNotNull { it.toEpisodeDownload(notMetRequirements, failureOf) }

        val downloading: Boolean get() = rows.any { it.state == Download.STATE_DOWNLOADING }
    }

    /**
     * What the engine holds, as one stream every reader shares.
     *
     * Two things wake it. One is the engine's own listener, which fires on state changes —
     * added, removed, finished, failed. The other is a one-second tick, and it is not an
     * optimisation: media3 never notifies on **progress**. The downloader mutates a
     * `DownloadProgress` object in memory and the manager flushes it to the index every five
     * seconds without telling anyone, so a reader driven by the listener alone would see nought
     * per cent for the whole download and then a hundred. [DownloadsSource.active] is where the
     * live objects are, and they are laid over the index rows by id.
     *
     * The tick runs only while something is downloading; with nothing in flight the loop parks on
     * the listener and the phone is left alone. One shared upstream rather than one per collector,
     * so the episode grid, the downloads screen and the storage line cost one listener and one
     * query between them.
     */
    private val snapshots: SharedFlow<Snapshot> = channelFlow {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val listener = object : DownloadsSource.Listener {
            override fun onChanged(download: Download, finalException: Exception?) {
                wake.trySend(Unit)
            }

            override fun onRemoved(download: Download) {
                wake.trySend(Unit)
            }

            override fun onIdle() {
                wake.trySend(Unit)
            }

            override fun onRequirementsChanged() {
                wake.trySend(Unit)
            }
        }
        source.addListener(listener)
        try {
            while (true) {
                val snapshot = read()
                send(snapshot)
                if (snapshot.downloading) {
                    // Whichever comes first: the engine saying something, or a second passing.
                    withTimeoutOrNull(PROGRESS_INTERVAL_MS) { wake.receive() }
                } else {
                    wake.receive()
                }
            }
        } finally {
            source.removeListener(listener)
        }
    }
        .flowOn(io)
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_KEEPALIVE_MS), replay = 1)

    /**
     * The index rows, with anything in flight replaced by the engine's live copy of it.
     *
     * The index is the only place a finished or failed download exists, and the live list the only
     * place progress is current, so neither on its own is the truth.
     */
    private fun read(): Snapshot {
        val live = source.active().associateBy { it.request.id }
        val stored = source.current()
        val rows = stored.map { live[it.request.id] ?: it } +
            live.values.filter { fresh -> stored.none { it.request.id == fresh.request.id } }
        return Snapshot(rows, source.notMetRequirements(), failures::messageFor)
    }

    override fun observeAll(): Flow<List<EpisodeDownload>> =
        combine(snapshots.map { it.downloads }, resolving) { downloads, pending ->
            merge(downloads, pending.values)
        }.distinctUntilChanged()

    override fun observe(animeId: Int): Flow<List<EpisodeDownload>> = observeAll()
        .map { all -> all.filter { it.animeId == animeId }.sortedBy { it.episode } }
        .distinctUntilChanged()

    /**
     * Bytes on the device. A row being deleted is already spoken for, so it is left out: counting
     * it would make the storage line tick down a second or two after the episode disappeared.
     */
    override val usedBytes: Flow<Long> = snapshots
        .map { snapshot ->
            snapshot.rows.filter { it.state != Download.STATE_REMOVING }.sumOf { it.bytesDownloaded }
        }
        .distinctUntilChanged()

    override suspend fun completed(animeId: Int, episode: Int): EpisodeDownload? = withContext(io) {
        source.current()
            .firstOrNull { it.state == Download.STATE_COMPLETED && it.matches(animeId, episode) }
            ?.toEpisodeDownload(notMetRequirements = 0, failureOf = failures::messageFor)
    }

    override suspend fun enqueue(animeId: Int, episode: Int, quality: Quality?): Result<Unit> {
        val policy = settings.downloadPolicy.first()
        val existing = withContext(io) { source.current() }
        val used = existing.sumOf { it.bytesDownloaded }
        if (!policy.fits(used, estimate(existing))) {
            // limitBytes is non-null here: a policy with no limit fits everything.
            return Result.failure(DownloadLimitReached(policy.limitBytes ?: Long.MAX_VALUE, used))
        }

        val wanted = quality ?: policy.quality
        val placeholder = placeholderId(animeId, episode)
        // A second press while the first is still resolving is the same request. Letting it
        // through would have two coroutines share one placeholder, and whichever finished first
        // would take it away under the other.
        var claimed = false
        resolving.update { pending ->
            claimed = placeholder !in pending
            if (claimed) pending + (placeholder to resolving(animeId, episode, wanted)) else pending
        }
        if (!claimed) return Result.success(Unit)

        try {
            // Every failure leaves as a Result, including one thrown rather than returned: the
            // interface promises a Result, and a screen that let an exception through would take
            // the app down for a link that did not resolve.
            return runCatching {
                // persist = false: preparing episode 12 must not move the row that says the viewer
                // is on episode 3 — that row carries their position, and rewriting it loses it.
                val stream = resolve(animeId, episode, persist = false).getOrThrow()
                val chosen = stream.pick(wanted)
                val key = DownloadKey(animeId, episode, stream.translation.id, chosen)

                // The same episode at another height, or in another voice, is a different file.
                // Only one copy of an episode is worth keeping, so the old one goes before the
                // new one starts.
                existing.forEach { download ->
                    val other = DownloadKey.parse(download.request.id) ?: return@forEach
                    if (other.animeId == animeId && other.episode == episode && other.id != key.id) {
                        commands.remove(other.id)
                    }
                }

                val payload = DownloadPayload.of(key, titleOf(animeId), stream.translation)
                commands.add(
                    DownloadRequest.Builder(key.id, stream.urls.getValue(chosen).toUri())
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setData(payload.encode())
                        .build(),
                )
                // A fresh request is not a failed one any more, whatever the last one said.
                failures.forget(key.id)
                awaitEngine(animeId, episode)
            }
        } finally {
            resolving.update { it - placeholder }
        }
    }

    override suspend fun remove(animeId: Int, episode: Int) = withContext(io) {
        source.current().filter { it.matches(animeId, episode) }.forEach { commands.remove(it.request.id) }
    }

    override suspend fun removeAll(animeId: Int) = withContext(io) {
        source.current()
            .filter { DownloadKey.parse(it.request.id)?.animeId == animeId }
            .forEach { commands.remove(it.request.id) }
    }

    override suspend fun removeAll() = commands.removeAll()

    /**
     * The engine's rows first, then the placeholders for episodes it has not been told about yet.
     *
     * An engine row always wins: the moment a request lands, «резолвим» is over, and showing both
     * would put the same episode on screen twice.
     */
    private fun merge(downloads: List<EpisodeDownload>, pending: Collection<EpisodeDownload>): List<EpisodeDownload> {
        val known = downloads.mapTo(mutableSetOf()) { it.animeId to it.episode }
        val extra = pending.filter { (it.animeId to it.episode) !in known }
        return (downloads + extra).sortedByDescending { it.updatedAt }
    }

    private fun resolving(animeId: Int, episode: Int, quality: Quality?) = EpisodeDownload(
        // Translation 0 means «not resolved yet»: a real Kodik track id is never 0, and the screens
        // only need the anime and the episode to draw a cell that is being prepared.
        key = DownloadKey(animeId, episode, UNRESOLVED_TRANSLATION, quality ?: DEFAULT_PLACEHOLDER_QUALITY),
        state = DownloadState.RESOLVING,
        bytes = 0,
        progress = 0f,
        failure = null,
        updatedAt = clock.instant(),
    )

    private fun placeholderId(animeId: Int, episode: Int) = "$animeId:$episode"

    /**
     * Waits for the engine to admit it has the request before the placeholder is taken away.
     *
     * The engine answers on its own thread, a moment after the request is handed over. Dropping
     * «резолвим» before that moment would leave the cell blank for a frame — the one thing the
     * placeholder exists to prevent. Bounded, because a request the engine never takes (a service
     * the platform refused to start) must not leave a viewer's button spinning.
     */
    private suspend fun awaitEngine(animeId: Int, episode: Int) {
        withTimeoutOrNull(ENGINE_ACK_TIMEOUT_MS) {
            snapshots.first { snapshot -> snapshot.rows.any { it.matches(animeId, episode) } }
        }
    }

    private fun Download.matches(animeId: Int, episode: Int): Boolean =
        DownloadKey.parse(request.id)?.let { it.animeId == animeId && it.episode == episode } == true

    /**
     * What the next episode is likely to weigh: the average of what this device has already
     * finished, or 400 MB before there is anything to average.
     */
    private fun estimate(downloads: List<Download>): Long {
        val finished = downloads.filter { it.state == Download.STATE_COMPLETED && it.bytesDownloaded > 0 }
        if (finished.isEmpty()) return DownloadPolicy.FALLBACK_ESTIMATE
        return finished.sumOf { it.bytesDownloaded } / finished.size
    }

    /**
     * The height to download at: the one asked for, the highest below it when the source has no
     * such rung, and the lowest it does have when everything on offer is higher. Never silently
     * bigger than what was asked for — the request is usually about saving space.
     */
    private fun EpisodeStream.pick(wanted: Quality?): Quality {
        if (wanted == null) return best
        if (urls.containsKey(wanted)) return wanted
        return urls.keys.filter { it.height < wanted.height }.maxByOrNull { it.height }
            ?: urls.keys.minBy { it.height }
    }

    /** The title for the notification, or null when this device has never seen the anime's card. */
    private suspend fun titleOf(animeId: Int): String? = runCatching {
        library.get().observeAnimeDetails(animeId).first()?.title
    }.getOrNull()

    private companion object {
        const val UNRESOLVED_TRANSLATION = 0
        val DEFAULT_PLACEHOLDER_QUALITY = Quality.P720

        /** Long enough for a service start, short enough that a refused one is not a hang. */
        const val ENGINE_ACK_TIMEOUT_MS = 2_000L

        /** How often a running download is re-read; media3 never reports progress on its own. */
        const val PROGRESS_INTERVAL_MS = 1_000L

        /** A screen rotation must not tear the upstream down and build the engine again. */
        const val SHARE_KEEPALIVE_MS = 5_000L
    }
}

@UnstableApi
internal fun Download.toEpisodeDownload(
    notMetRequirements: Int,
    failureOf: (String) -> String,
): EpisodeDownload? {
    val key = DownloadKey.parse(request.id) ?: return null
    val mapped = when (state) {
        Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
        Download.STATE_RESTARTING -> DownloadState.DOWNLOADING
        Download.STATE_COMPLETED -> DownloadState.COMPLETED
        Download.STATE_FAILED -> DownloadState.FAILED
        Download.STATE_REMOVING -> DownloadState.REMOVING
        // Queued and stopped look the same to a viewer; what separates them is why nothing is
        // moving, and «ждём Wi-Fi» is the only reason worth its own word on screen.
        else -> if (notMetRequirements != 0) DownloadState.WAITING_FOR_WIFI else DownloadState.QUEUED
    }
    return EpisodeDownload(
        key = key,
        state = mapped,
        bytes = bytesDownloaded,
        // media3 reports -1 until it knows the size of the whole episode.
        progress = percentDownloaded.takeIf { it.isFinite() && it > 0f }?.let { it / 100f }?.coerceIn(0f, 1f) ?: 0f,
        failure = if (mapped == DownloadState.FAILED) failureOf(request.id) else null,
        updatedAt = Instant.ofEpochMilli(updateTimeMs),
    )
}
