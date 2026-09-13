package app.kaeru

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaeru.player.CastFramework
import app.kaeru.player.CastSessionBridge
import app.kaeru.ui.mobile.MobileApp
import app.kaeru.ui.mobile.OAuthCallback
import app.kaeru.ui.common.player.LocalCastAvailable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /** A `FragmentActivity` because the Cast route chooser is a dialog fragment. */
    @Inject lateinit var cast: CastFramework

    @Inject lateinit var castSessions: CastSessionBridge

    private var pendingCallback by mutableStateOf<OAuthCallback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readAuthCallback(intent)
        // Listening from here too, so a session that ends while the player is closed still
        // brings playback back to the phone.
        castSessions.start()
        setContent {
            // Observed, not read: the framework comes up a moment after launch, and the button
            // has to appear then rather than never.
            val castAvailable by cast.isAvailable.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalCastAvailable provides castAvailable) {
                MobileApp(callback = pendingCallback, onCallbackConsumed = { pendingCallback = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAuthCallback(intent)
    }

    /**
     * Reads one `kaeru://oauth` callback and strips it from the intent, so a recreation (rotation,
     * process restart) cannot replay it. Validation of `state` belongs to the auth layer: this
     * only carries the parameters across.
     */
    private fun readAuthCallback(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "kaeru" || data.host != "oauth") return
        pendingCallback = OAuthCallback(data.getQueryParameter("code"), data.getQueryParameter("state"))
        intent.data = null
    }
}
