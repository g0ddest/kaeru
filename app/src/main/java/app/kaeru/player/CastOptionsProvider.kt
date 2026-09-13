package app.kaeru.player

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

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
        .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
