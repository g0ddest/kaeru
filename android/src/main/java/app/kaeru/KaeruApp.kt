package app.kaeru

import android.app.Activity
import android.app.Application
import app.kaeru.data.together.TogetherLog
import app.kaeru.data.report.Reporting
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadEngine
import app.kaeru.data.image.PosterWarmer
import app.kaeru.data.library.OfflineSyncStarter
import app.kaeru.data.notify.NewEpisodesStarter
import app.kaeru.data.update.UpdateCheckStarter
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
class KaeruApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    @Inject lateinit var downloads: DownloadEngine

    /** The one process-long scope: the outbox drain and the download engine both outlive every screen. */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Inject lateinit var posters: PosterWarmer

    @Inject lateinit var deleteWatchedDownloads: DeferredDownloadRemoval

    @Inject lateinit var images: ImageLoader

    @Inject lateinit var updates: UpdateCheckStarter

    @Inject lateinit var newEpisodes: NewEpisodesStarter

    /** What lets a `@HiltWorker` be built with the rest of the graph behind it. */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Analytics and crash reports first, so a crash anywhere below is one that gets reported.
        Reporting.install(this, television = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        // The shared-viewing journal, where `adb pull` can reach it on a release build.
        TogetherLog.install(this)
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
        // Whether a newer release exists, asked at most once a day and never waited for. The
        // answer is written down; the home screen shows it whenever it lands, which may well be
        // after the screen is already up.
        updates.start(appScope)
        // Whether the six-hourly look for a new episode should be on at all. It watches rather
        // than decides once: signing out has to take the work off, and signing back in has to put
        // it on again, and neither happens with a screen around to ask.
        newEpisodes.start(appScope)
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

    /**
     * WorkManager built on demand rather than by its own startup provider, which is what the
     * manifest removes.
     *
     * The factory is the whole reason: without it a worker is constructed reflectively with only a
     * context and its parameters, and this app's one worker needs the library, the database and
     * the notifier behind it.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
