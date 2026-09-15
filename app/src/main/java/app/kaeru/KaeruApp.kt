package app.kaeru

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadEngine
import app.kaeru.data.image.PosterWarmer
import app.kaeru.data.library.OfflineSyncStarter
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.di.ApplicationScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Marked whole rather than member by member, which is the one place in this app that is true.
 *
 * Hilt generates a members-injector for this class that names [DownloadEngine], and lint reads the
 * generated code as part of this file: an `@OptIn` on the property and on `onCreate` still leaves
 * a file-level `UnsafeOptInUsageError` here, with no declaration left to annotate. Verified by
 * trying it — `:app:lintDebug` fails on `KaeruApp.kt` with no line number to fix.
 */
@UnstableApi
@HiltAndroidApp
class KaeruApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    @Inject lateinit var downloads: DownloadEngine

    /** The one process-long scope: the outbox drain and the download engine both outlive every screen. */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Inject lateinit var posters: PosterWarmer

    @Inject lateinit var deleteWatchedDownloads: DeferredDownloadRemoval

    @Inject lateinit var images: ImageLoader

    override fun onCreate() {
        super.onCreate()
        offlineSync.start()
        // Downloads outlive every screen too: the policy has to reach the engine, an expired
        // Kodik signature has to be replaced, and whatever last night's queue left unfinished
        // has to start again — none of which anything on screen is around to ask for.
        downloads.start(appScope)
        // Artwork for what has been downloaded, fetched while there is still a network to fetch it
        // with. A poster is kilobytes against an episode's megabytes, and without it the offline
        // screens are a list of grey rectangles.
        posters.start(appScope)
        // Whatever «удалять просмотренные» owed when the process last died. A deletion waits for
        // playback to move off the episode, and a process that goes away first never gets there.
        deleteWatchedDownloads.start(appScope)
        registerActivityLifecycleCallbacks(ForegroundWatch())
    }

    /**
     * Tells the download engine when the app is somewhere the platform will let it start a
     * service.
     *
     * A process the system started in the background — restarting the download service, say —
     * runs `onCreate` where Android refuses a foreground start outright, and nothing after that
     * would try again. A resumed activity is the first moment it can work. Registered rather than
     * observed through `ProcessLifecycleOwner`, which would mean a dependency this app does not
     * have; the engine ignores the call unless a start was actually refused.
     */
    private inner class ForegroundWatch : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = downloads.onForeground()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * The loader every `AsyncImage` in the app resolves to.
     *
     * Handed Hilt's instance rather than built here, so the disk cache Compose reads is the one
     * [PosterWarmer] writes into. Coil asks for this lazily, on the first image, which is long
     * after injection.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = images
}
