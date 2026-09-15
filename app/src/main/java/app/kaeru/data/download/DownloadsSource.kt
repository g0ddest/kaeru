package app.kaeru.data.download

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Everything this app reads back from the download engine.
 *
 * The other half of [DownloadCommands], and for the same reason: a real `DownloadManager` needs a
 * Looper, a database and a cache directory, none of which a unit test should have to stand up to
 * assert that a 403 leads to one re-add.
 */
@UnstableApi
interface DownloadsSource {

    /**
     * Every download the engine holds, in every state — finished and failed ones included.
     *
     * Reads the engine's own index rather than its list of downloads in flight, because a
     * finished episode is exactly the one the player and the storage line most need to see.
     * It queries a database, so callers keep it off the main thread.
     */
    fun current(): List<Download>

    /** The requirements the device does not meet right now; 0 when nothing is holding downloads back. */
    fun notMetRequirements(): Int

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    /**
     * What the engine reports as it happens.
     *
     * [onChanged] carries the exception media3 hands to its own listener, which is the only place
     * the HTTP status behind a failure is visible: `Download.failureReason` is a single
     * «unknown» bit, and by the time the row is read back the reason is gone.
     */
    interface Listener {
        fun onChanged(download: Download, finalException: Exception?)
        fun onRemoved(download: Download)
        fun onIdle()
    }
}

/** The engine itself. */
@UnstableApi
@Singleton
class Media3DownloadsSource @Inject constructor(
    /**
     * Asked for lazily: building the manager opens a database and scans the cache directory, so
     * it happens on the first read, which callers already keep off the main thread.
     */
    private val downloads: Provider<DownloadManager>,
) : DownloadsSource {

    private val manager: DownloadManager get() = downloads.get()

    /** One media3 listener per listener of ours, so removing takes away the right one. */
    private val bridges = ConcurrentHashMap<DownloadsSource.Listener, DownloadManager.Listener>()

    override fun current(): List<Download> = try {
        manager.downloadIndex.getDownloads().use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.download) }
        }
    } catch (unreadable: IOException) {
        // A download index that cannot be read is an empty one as far as the screens go. Saying
        // so beats failing a flow the whole downloads screen is collecting.
        Log.w(TAG, "Download index could not be read", unreadable)
        emptyList()
    }

    override fun notMetRequirements(): Int = manager.notMetRequirements

    override fun addListener(listener: DownloadsSource.Listener) {
        val bridge = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) = listener.onChanged(download, finalException)

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) =
                listener.onRemoved(download)

            override fun onIdle(downloadManager: DownloadManager) = listener.onIdle()
        }
        bridges[listener] = bridge
        manager.addListener(bridge)
    }

    override fun removeListener(listener: DownloadsSource.Listener) {
        bridges.remove(listener)?.let(manager::removeListener)
    }

    private companion object {
        const val TAG = "DownloadsSource"
    }
}
