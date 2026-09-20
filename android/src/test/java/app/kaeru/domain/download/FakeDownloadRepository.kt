package app.kaeru.domain.download

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * The download engine without an engine: rows a test seeds by hand, and a record of everything
 * the app asked it to do.
 *
 * [downloaded] seeds the two halves of one finished episode together — the row the screens draw
 * and the stream the player opens — because in the real repository they are two readings of the
 * same index entry and a test where they disagree is a test of nothing.
 */
class FakeDownloadRepository : DownloadRepository {

    private val rows = MutableStateFlow<List<EpisodeDownload>>(emptyList())
    private val streams = MutableStateFlow<Map<Pair<Int, Int>, EpisodeStream>>(emptyMap())

    /** Every enqueue, in order: the episode and what was asked of its height. */
    val enqueued = mutableListOf<Triple<Int, Int, DownloadQualityChoice?>>()

    /** Every episode-sized removal, in order. */
    val removed = mutableListOf<Pair<Int, Int>>()

    val removedTitles = mutableListOf<Int>()
    var removedEverything = 0
        private set

    /** While set, [enqueue] refuses with it — a limit reached, or a resolve that failed. */
    var enqueueFailure: Throwable? = null

    private val usage = MutableStateFlow(0L)

    /**
     * What the engine says is on the device, which is not the sum of [rows].
     *
     * The two are separate on purpose: the real engine counts bytes on disk, and a screen that
     * added its own rows up would quietly disagree with it the first time a partial download or a
     * shared segment appeared.
     */
    fun setUsedBytes(bytes: Long) {
        usage.value = bytes
    }

    /** One finished episode, as both the row and the stream the player would open. */
    fun downloaded(
        animeId: Int,
        episode: Int,
        translation: Translation,
        url: String,
        quality: Quality = Quality.P720,
        bytes: Long = 320L * 1024 * 1024,
        at: Instant = Instant.parse("2026-09-13T10:00:00Z"),
    ) {
        val key = DownloadKey(animeId, episode, translation.id, quality)
        put(EpisodeDownload(key, DownloadState.COMPLETED, bytes, 1f, failure = null, updatedAt = at))
        streams.update {
            it + ((animeId to episode) to EpisodeStream(animeId, episode, translation, mapOf(quality to url), at))
        }
    }

    /** A row in any state; nothing is playable from it unless [downloaded] put a stream there too. */
    fun put(download: EpisodeDownload) = rows.update { current ->
        current.filterNot { it.animeId == download.animeId && it.episode == download.episode } + download
    }

    override fun observeAll(): Flow<List<EpisodeDownload>> = rows

    override fun observe(animeId: Int): Flow<List<EpisodeDownload>> =
        rows.map { all -> all.filter { it.animeId == animeId }.sortedBy { it.episode } }

    override suspend fun completed(animeId: Int, episode: Int): EpisodeDownload? = rows.value
        .firstOrNull { it.animeId == animeId && it.episode == episode && it.state == DownloadState.COMPLETED }

    override suspend fun completedStream(animeId: Int, episode: Int): EpisodeStream? =
        completed(animeId, episode)?.let { streams.value[animeId to episode] }

    override suspend fun enqueue(animeId: Int, episode: Int, quality: DownloadQualityChoice?): Result<Unit> {
        enqueued += Triple(animeId, episode, quality)
        return enqueueFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * While set, a removal is recorded and then nothing happens, the way the real engine behaves:
     * `remove` only sends a request to a service that gets to it later, and the row stays in the
     * index until it does. [releaseRemovals] is that service finally getting to it.
     */
    var holdRemovals = false

    /**
     * While set, a removal is recorded and refused: the command never reached the service at all,
     * the way Android refuses a `startService` from a process the viewer cannot see. Apart from
     * [holdRemovals] — held is accepted-but-not-yet-done, refused is never sent anywhere.
     */
    var refuseRemovals = false

    private val held = mutableListOf<Pair<Int, Int>>()

    fun releaseRemovals() {
        val pending = held.toList()
        held.clear()
        pending.forEach { (animeId, episode) -> forget(animeId, episode) }
    }

    override suspend fun remove(animeId: Int, episode: Int): Boolean {
        removed += animeId to episode
        if (refuseRemovals) return false
        if (holdRemovals) {
            held += animeId to episode
            return true
        }
        forget(animeId, episode)
        return true
    }

    private fun forget(animeId: Int, episode: Int) {
        rows.update { all -> all.filterNot { it.animeId == animeId && it.episode == episode } }
        streams.update { it - (animeId to episode) }
    }

    override suspend fun removeAll(animeId: Int) {
        removedTitles += animeId
        rows.update { all -> all.filterNot { it.animeId == animeId } }
        streams.update { map -> map.filterKeys { it.first != animeId } }
    }

    override suspend fun removeAll() {
        removedEverything += 1
        rows.value = emptyList()
        streams.value = emptyMap()
    }

    override val usedBytes: Flow<Long> = usage
}
