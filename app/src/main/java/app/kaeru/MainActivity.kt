package app.kaeru

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.kaeru.ui.mobile.MobileApp
import app.kaeru.ui.mobile.OAuthCallback
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingCallback by mutableStateOf<OAuthCallback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readAuthCallback(intent)
        setContent {
            MobileApp(callback = pendingCallback, onCallbackConsumed = { pendingCallback = null })
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
