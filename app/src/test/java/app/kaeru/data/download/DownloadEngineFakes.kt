package app.kaeru.data.download

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The download engine as a test can hold it.
 *
 * media3's own classes are used for what crosses the seam — a `Download` is a plain value object
 * and building one is cheaper than inventing a parallel type — but nothing here needs a Looper,
 * a cache directory or a database.
 */

/** A row the engine could be holding. */
@UnstableApi
fun downloadOf(
    request: DownloadRequest,
    state: Int = Download.STATE_QUEUED,
    bytes: Long = 0,
    percent: Float = 0f,
    failureReason: Int = Download.FAILURE_REASON_NONE,
    updatedAtMs: Long = 0,
): Download = Download(
    request,
    state,
    /* startTimeMs = */ 0L,
    /* updateTimeMs = */ updatedAtMs,
    C.LENGTH_UNSET.toLong(),
    Download.STOP_REASON_NONE,
    failureReason,
    DownloadProgress().apply {
        bytesDownloaded = bytes
        percentDownloaded = percent
    },
)

/** What the engine is holding, and what it tells its listeners as that changes. */
@UnstableApi
class FakeDownloadsSource : DownloadsSource {
    private val rows = LinkedHashMap<String, Download>()
    private val listeners = CopyOnWriteArrayList<DownloadsSource.Listener>()

    /** The requirements the device does not meet, as the policy would report them. */
    var notMet: Int = 0

    /** Collectors that have a listener registered right now; a leak shows up as a number that grows. */
    val listenerCount: Int get() = listeners.size

    override fun current(): List<Download> = rows.values.toList()

    override fun notMetRequirements(): Int = notMet

    override fun addListener(listener: DownloadsSource.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: DownloadsSource.Listener) {
        listeners -= listener
    }

    /** Puts a row in, or replaces the one with the same id, and says so. */
    fun put(download: Download, cause: Exception? = null) {
        rows[download.request.id] = download
        listeners.forEach { it.onChanged(download, cause) }
    }

    fun drop(id: String) {
        rows.remove(id)?.let { gone -> listeners.forEach { it.onRemoved(gone) } }
    }

    fun clear() {
        val gone = rows.values.toList()
        rows.clear()
        gone.forEach { row -> listeners.forEach { it.onRemoved(row) } }
    }

    fun row(id: String): Download? = rows[id]
}

/** Every command the repository and the refresher send, recorded in order. */
@UnstableApi
class FakeDownloadCommands(private val engine: FakeDownloadsSource? = null) : DownloadCommands {
    val added = mutableListOf<DownloadRequest>()
    val removed = mutableListOf<String>()
    val requirements = mutableListOf<Requirements>()
    val resumed = mutableListOf<String>()
    var clearedAll = 0
        private set

    override fun add(request: DownloadRequest) {
        added += request
        // The real engine merges by id and starts the download queued, which is what the screens
        // then see; a fake that only recorded would make every flow test assert on nothing.
        engine?.put(downloadOf(request, Download.STATE_QUEUED))
    }

    override fun remove(id: String) {
        removed += id
        engine?.drop(id)
    }

    override fun removeAll() {
        clearedAll += 1
        engine?.clear()
    }

    override fun setRequirements(requirements: Requirements) {
        this.requirements += requirements
    }

    override fun resume(id: String) {
        resumed += id
    }

    fun clear() {
        added.clear()
        removed.clear()
        requirements.clear()
        resumed.clear()
        clearedAll = 0
    }
}
