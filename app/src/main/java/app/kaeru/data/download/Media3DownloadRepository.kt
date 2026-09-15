package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
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
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DownloadRepository {

    /** Episodes whose link is being resolved, keyed by anime and episode — no more than a few. */
    private val resolving = MutableStateFlow<Map<String, EpisodeDownload>>(emptyMap())

    private val started = AtomicBoolean(false)

    /**
     * Starts pushing the policy's network rule at the engine.
     *
     * Separate from construction because it needs a scope that outlives every screen: the rule has
     * to follow a settings change even when nothing is on screen to observe it. Idempotent, so a
     * second call — a restarted process reusing the same singleton — changes nothing.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settings.downloadPolicy
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    val network = if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
                    commands.setRequirements(Requirements(network))
                }
        }
    }

    override fun observeAll(): Flow<List<EpisodeDownload>> =
        combine(engine().map { it.downloads }, resolving) { downloads, pending ->
            merge(downloads, pending.values)
        }.distinctUntilChanged()

    override fun observe(animeId: Int): Flow<List<EpisodeDownload>> = observeAll()
        .map { all -> all.filter { it.animeId == animeId }.sortedBy { it.episode } }
        .distinctUntilChanged()

    override val usedBytes: Flow<Long> = engine()
        .map { snapshot -> snapshot.rows.sumOf { it.bytesDownloaded } }
        .distinctUntilChanged()

    override suspend fun completed(animeId: Int, episode: Int): EpisodeDownload? = withContext(io) {
        source.current()
            .firstOrNull { it.state == Download.STATE_COMPLETED && it.matches(animeId, episode) }
            ?.toEpisodeDownload(notMetRequirements = 0)
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
        resolving.update { it + (placeholder to resolving(animeId, episode, wanted)) }
        try {
            // persist = false: preparing episode 12 must not move the row that says the viewer
            // is on episode 3 — that row carries their position, and rewriting it loses it.
            val stream = resolve(animeId, episode, persist = false).getOrElse { return Result.failure(it) }
            val chosen = stream.pick(wanted)
            val key = DownloadKey(animeId, episode, stream.translation.id, chosen)

            // The same episode at another height, or in another voice, is a different file. Only
            // one copy of an episode is worth keeping, so the old one goes before the new one starts.
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
            awaitEngine(animeId, episode)
            return Result.success(Unit)
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

    // ---- reading the engine ------------------------------------------------------------------

    private class Snapshot(val rows: List<Download>, notMetRequirements: Int) {
        val downloads: List<EpisodeDownload> = rows.mapNotNull { it.toEpisodeDownload(notMetRequirements) }
    }

    /**
     * What the engine holds, re-read on every change it reports.
     *
     * Each collector registers its own listener rather than sharing one snapshot flow, because
     * sharing would need a scope this class does not own until [start] and screens observe before
     * that. A listener is a set entry and the re-read is one indexed query, so the cost of a few
     * collectors is not worth a lifecycle.
     */
    private fun engine(): Flow<Snapshot> = callbackFlow {
        val listener = object : DownloadsSource.Listener {
            override fun onChanged(download: Download, finalException: Exception?) {
                trySend(Unit)
            }

            override fun onRemoved(download: Download) {
                trySend(Unit)
            }

            override fun onIdle() {
                trySend(Unit)
            }
        }
        source.addListener(listener)
        trySend(Unit)
        awaitClose { source.removeListener(listener) }
    }
        // A burst of progress callbacks is one re-read, not one per callback.
        .conflate()
        .map { Snapshot(source.current(), source.notMetRequirements()) }
        .flowOn(io)

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
            engine().first { snapshot -> snapshot.rows.any { it.matches(animeId, episode) } }
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
    }
}

/** What a failed download says to the viewer; the refresher has already tried and given up. */
internal const val LINK_EXPIRED = "Ссылка устарела, попробуйте позже"

@UnstableApi
internal fun Download.toEpisodeDownload(notMetRequirements: Int): EpisodeDownload? {
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
        failure = if (mapped == DownloadState.FAILED) LINK_EXPIRED else null,
        updatedAt = Instant.ofEpochMilli(updateTimeMs),
    )
}
