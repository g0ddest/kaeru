package app.kaeru

import android.app.Application
import app.kaeru.data.library.OfflineSyncStarter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class KaeruApp : Application() {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    /**
     * Lives as long as the process does, and is never cancelled: what it carries is the queue of
     * marks a viewer made without a network, which has to outlive every screen they made them on.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        offlineSync.start(appScope)
    }
}
