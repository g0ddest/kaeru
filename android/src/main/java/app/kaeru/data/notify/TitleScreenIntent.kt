package app.kaeru.data.notify

import android.content.Intent

/**
 * How a notification asks the app to open one title's screen.
 *
 * The same shape as `DownloadsScreenIntent` and for the same reason: nothing in the data layer
 * names an activity or a route. The module in `di` that builds it is the one place that sees both.
 */
fun interface TitleScreenIntent {
    fun create(animeId: Int): Intent
}
