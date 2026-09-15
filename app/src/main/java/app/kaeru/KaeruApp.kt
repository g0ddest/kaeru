package app.kaeru

import android.app.Application
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadEngine
import app.kaeru.data.library.OfflineSyncStarter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@UnstableApi
@HiltAndroidApp
class KaeruApp : Application() {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    @Inject lateinit var downloads: DownloadEngine

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
    }
}
