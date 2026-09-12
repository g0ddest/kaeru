package app.kaeru.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * How this app casts. The Cast framework finds this class through the `OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data in the manifest and builds it itself, which is why it has no dependencies.
 *
 * The receiver is Google's own Default Media Receiver: Kodik serves plain HLS with
 * `Access-Control-Allow-Origin: *` and no header requirements, so there is nothing for a custom
 * receiver to do that the default one cannot. If a device ever refuses these manifests, the way
 * out is a CAF receiver of our own that rewrites the requests — not a change here.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // Leaving a stopped receiver on our app would keep somebody's television on a black
        // screen with our episode's name under it.
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
