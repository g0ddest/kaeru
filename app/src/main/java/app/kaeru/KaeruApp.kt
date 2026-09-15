package app.kaeru

import android.app.Application
import app.kaeru.data.library.OfflineSyncStarter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KaeruApp : Application() {
    @Inject lateinit var offlineSync: OfflineSyncStarter

    override fun onCreate() {
        super.onCreate()
        offlineSync.start()
    }
}
