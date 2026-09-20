package app.kaeru.data.notify

import android.content.Intent

/**
 * How a notification asks for one episode to start playing.
 *
 * The same shape as [TitleScreenIntent], and here for the same reason: the player is an activity in
 * `ui.mobile`, and nothing in the data layer names one. The module in `di` is the one place that
 * sees both.
 */
fun interface WatchEpisodeIntent {
    fun create(animeId: Int, episode: Int): Intent
}
