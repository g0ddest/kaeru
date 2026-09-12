package app.kaeru.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Publishes the one player this process owns as a media session, which is what gives the app
 * a notification with transport controls, headphone and Bluetooth buttons, and playback that
 * survives leaving the player screen.
 *
 * It creates no player of its own: the same [PlaybackEngine] the controller drives is the one
 * the session exposes, so there is never a second thing playing.
 */
@UnstableApi
@AndroidEntryPoint
class KaeruPlaybackService : MediaSessionService() {

    @Inject lateinit var engine: PlaybackEngine

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = engine.videoPlayer ?: return
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away should not leave a stray notification playing to nobody. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
