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
 * It creates no player of its own: the same [ExoPlaybackEngine] the controller drives is the one
 * the session exposes, so there is never a second thing playing. It does own the player's
 * lifetime, though — when this service goes away with nothing loaded, the player is given back
 * and the next episode builds a new one.
 */
@UnstableApi
@AndroidEntryPoint
class KaeruPlaybackService : MediaSessionService() {

    @Inject lateinit var engine: ExoPlaybackEngine

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, engine.acquirePlayer()).build()
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
        // The player screen finishing stops the player and clears it before stopping this
        // service, so by now there is usually nothing loaded and the decoder can go back. When
        // something is still loaded the engine keeps it: see ExoPlaybackEngine.shutdownIfIdle.
        engine.shutdownIfIdle()
        super.onDestroy()
    }
}
