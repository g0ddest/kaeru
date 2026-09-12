package app.kaeru

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.kaeru.ui.mobile.MobileApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingAuthCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readAuthCode(intent)
        setContent {
            MobileApp(pendingAuthCode = pendingAuthCode, onAuthCodeConsumed = { pendingAuthCode = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAuthCode(intent)
    }

    private fun readAuthCode(intent: Intent?) {
        if (intent?.data?.scheme == "kaeru" && intent.data?.host == "oauth") {
            pendingAuthCode = intent.data?.getQueryParameter("code")
        }
    }
}
