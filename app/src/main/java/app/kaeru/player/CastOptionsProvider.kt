package app.kaeru.player

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * The Styled Media Receiver registered for Kaeru in the Google Cast SDK Developer Console.
 * Not a secret: every sender ships its receiver id in the clear. Until the application is
 * published there, it launches only on Cast devices registered in the same console.
 *
 * Its look (logo, splash, progress colour) is a CSS skin the console points to; the skin itself
 * lives in `docs/cast/` of this repository.
 */
const val KAERU_RECEIVER_APPLICATION_ID = "0EEA38FE"

/**
 * How this app casts. The Cast framework finds this class through the `OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data in the manifest and builds it itself, which is why it has no dependencies.
 *
 * The receiver is Google's styled media receiver under our own id: Kodik serves plain HLS with
 * `Access-Control-Allow-Origin: *` and no header requirements, so there is nothing for a custom
 * receiver to do that the styled one cannot, and the id only buys our branding on the television.
 * If a device ever refuses these manifests, the way out is a CAF receiver of our own that rewrites
 * the requests — a different application in the console, not a change here.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(KAERU_RECEIVER_APPLICATION_ID)
        // Leaving a stopped receiver on our app would keep somebody's television on a black
        // screen with our episode's name under it.
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(notificationOptions())
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null

    /**
     * The notification shown while a receiver has the picture.
     *
     * The app's own media3 notification cannot serve here: it belongs to the local player, which
     * is stopped and emptied the moment a session starts, so while casting the shade held nothing
     * at all — an episode playing across the room with no way to pause it but to find the phone,
     * unlock it and open the app. This one is the Cast SDK's, driven by the receiver's own media
     * status, so it says what is playing and stays right when somebody else's remote pauses it.
     *
     * Two actions, which is what the compact view shows: pause, and stop casting. Seeking and
     * episode changes belong to the remote-control screen, which the notification opens.
     */
    private fun notificationOptions() = NotificationOptions.Builder()
        .setActions(
            listOf(MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_STOP_CASTING),
            intArrayOf(0, 1),
        )
        .setTargetActivityClassName(PLAYER_ACTIVITY)
        .build()
}

/**
 * Where the cast notification goes when it is tapped, by name.
 *
 * A name rather than a class reference on purpose: this file is in `player`, the activity is in
 * `ui.mobile.player`, and playback does not import screens. `CastOptionsProviderTest` holds the
 * two together, so a renamed or moved activity fails a test rather than a tap on a notification.
 */
internal const val PLAYER_ACTIVITY = "app.kaeru.ui.mobile.player.PlayerActivity"
