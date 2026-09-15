package app.kaeru

import android.app.Application
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadEngine
import app.kaeru.data.image.PosterWarmer
import app.kaeru.data.library.OfflineSyncStarter
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@UnstableApi
@HiltAndroidApp
class KaeruApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    @Inject lateinit var downloads: DownloadEngine

    @Inject lateinit var posters: PosterWarmer

    @Inject lateinit var images: ImageLoader

    /**
     * Lives as long as the process does, and is never cancelled: what it carries is the queue of
     * marks a viewer made without a network, which has to outlive every screen they made them on.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        offlineSync.start(appScope)
        // Downloads outlive every screen too: the policy has to reach the engine, an expired
        // Kodik signature has to be replaced, and whatever last night's queue left unfinished
        // has to start again — none of which anything on screen is around to ask for.
        downloads.start(appScope)
        // Artwork for what has been downloaded, fetched while there is still a network to fetch it
        // with. A poster is kilobytes against an episode's megabytes, and without it the offline
        // screens are a list of grey rectangles.
        posters.start(appScope)
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
