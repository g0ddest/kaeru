package app.kaeru.data.download

import android.content.Intent

/**
 * How a notification asks the app to open the downloads screen.
 *
 * An interface, and built in `di`, so that nothing in the data layer has to name an activity. The
 * route travels as an extra rather than as a deep link, because it names a screen inside the app
 * and nothing outside it should be able to fire it.
 */
fun interface DownloadsScreenIntent {
    fun create(): Intent

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_DOWNLOADS = "downloads"
    }
}
