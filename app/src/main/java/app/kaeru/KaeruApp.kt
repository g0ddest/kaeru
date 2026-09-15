package app.kaeru

import android.app.Application
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadEngine
import app.kaeru.data.library.OfflineSyncStarter
import app.kaeru.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@UnstableApi
@HiltAndroidApp
class KaeruApp : Application() {
    @Inject lateinit var offlineSync: OfflineSyncStarter
    @Inject lateinit var downloads: DownloadEngine

    /** The one process-long scope: the outbox drain and the download engine both outlive every screen. */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        offlineSync.start()
        // Downloads outlive every screen too: the policy has to reach the engine, an expired
        // Kodik signature has to be replaced, and whatever last night's queue left unfinished
        // has to start again — none of which anything on screen is around to ask for.
        downloads.start(appScope)
    }
}
