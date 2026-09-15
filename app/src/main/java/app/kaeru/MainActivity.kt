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
import androidx.media3.common.util.UnstableApi
import app.kaeru.data.download.DownloadNotifications
import app.kaeru.player.CastFramework
import app.kaeru.player.CastSessionBridge
import app.kaeru.ui.mobile.MobileApp
import app.kaeru.ui.mobile.OAuthCallback
import app.kaeru.ui.common.player.LocalCastAvailable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// The downloads notification's constants are the only unstable-API surface this file touches.
@UnstableApi
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /** A `FragmentActivity` because the Cast route chooser is a dialog fragment. */
    @Inject lateinit var cast: CastFramework

    @Inject lateinit var castSessions: CastSessionBridge

    private var pendingCallback by mutableStateOf<OAuthCallback?>(null)

    private var pendingPairing by mutableStateOf<String?>(null)

    /** A screen the app was asked to open from outside it: today, «Загрузки» from the notification. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDeepLink(intent)
        readRoute(intent)
        // Listening from here too, so a session that ends while the player is closed still
        // brings playback back to the phone.
        castSessions.start()
        setContent {
            // Observed, not read: the framework comes up a moment after launch, and the button
            // has to appear then rather than never.
            val castAvailable by cast.isAvailable.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalCastAvailable provides castAvailable) {
                MobileApp(
                    callback = pendingCallback,
                    onCallbackConsumed = { pendingCallback = null },
                    pairingLink = pendingPairing,
                    onPairingLinkConsumed = { pendingPairing = null },
                    route = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
        readRoute(intent)
    }

    /**
     * Reads the screen a notification asked for, and strips it from the intent.
     *
     * Stripped for the same reason a deep link is: the intent outlives the tap, and an app
     * recreated by a rotation would otherwise find «открой загрузки» in it again and navigate away
     * from wherever the viewer had got to since.
     */
    private fun readRoute(intent: Intent?) {
        val route = intent?.getStringExtra(DownloadNotifications.EXTRA_ROUTE) ?: return
        if (route == DownloadNotifications.ROUTE_DOWNLOADS) pendingRoute = route
        intent.removeExtra(DownloadNotifications.EXTRA_ROUTE)
    }

    /**
     * Reads one of the app's two deep links and strips it from the intent, so a recreation
     * (rotation, process restart) cannot replay it. Neither is trusted here: a `kaeru://oauth`
     * callback is validated by the auth layer and a `kaeru://pair` link by `PairingRequest`, which
     * refuses everything that does not point at a television on this network. This only carries
     * them across.
     */
    private fun readDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "kaeru") return
        when (data.host) {
            "oauth" -> pendingCallback = OAuthCallback(data.getQueryParameter("code"), data.getQueryParameter("state"))
            "pair" -> pendingPairing = data.toString()
            else -> return
        }
        intent.data = null
    }
}
