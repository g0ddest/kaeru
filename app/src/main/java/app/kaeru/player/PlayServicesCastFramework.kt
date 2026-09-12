package app.kaeru.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.kaeru.di.PlaybackScope
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Cast, behind the guard the rest of the app relies on.
 *
 * `CastContext.getSharedInstance` throws on a phone with no Google Play services, with Play
 * services too old for the Cast framework, or with the framework disabled — and that must cost
 * the cast button and nothing else. So it is asked for lazily, once, inside a `runCatching`,
 * and everything else here answers "no" when it did not work out.
 *
 * The Cast framework insists on the main thread. So does the playback scope this collects on,
 * and so is every caller: the player screen, the home screen and the session bridge.
 */
@UnstableApi
@Singleton
class PlayServicesCastFramework @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:PlaybackScope private val scope: CoroutineScope,
) : CastFramework {

    private val castContext: CastContext? by lazy {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }

    override val isAvailable: Boolean get() = castContext != null

    /** One per process: the receiver is one device, and a second player would fight the first. */
    private var castEngine: CastPlaybackEngine? = null

    override val connections: Flow<CastConnection> = callbackFlow {
        val sessions = castContext?.sessionManager ?: return@callbackFlow
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = connected()

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected()

            override fun onSessionEnded(session: CastSession, error: Int) = disconnected()

            override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected()

            override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()

            // A suspension is a network blip, not a disconnect: the framework reconnects by
            // itself, and yanking the episode back to the phone over one would be worse than
            // the pause the viewer is already seeing.
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

            override fun onSessionStarting(session: CastSession) = Unit

            override fun onSessionEnding(session: CastSession) = Unit

            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

            private fun connected() {
                trySend(CastConnection.CONNECTED)
            }

            private fun disconnected() {
                trySend(CastConnection.DISCONNECTED)
            }
        }
        sessions.addSessionManagerListener(listener, CastSession::class.java)
        // A session already running when the app comes back: the phone has to learn it is a
        // remote control before it tries to play anything locally.
        if (sessions.currentCastSession?.isConnected == true) trySend(CastConnection.CONNECTED)
        awaitClose { sessions.removeSessionManagerListener(listener, CastSession::class.java) }
    }

    override fun engine(): PlaybackEngine? {
        val framework = castContext ?: return null
        return castEngine ?: CastPlaybackEngine(framework, scope).also { castEngine = it }
    }

    /** Never throws: a disconnect that fails leaves the session up, which the viewer can see. */
    override fun endSession() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }
}
