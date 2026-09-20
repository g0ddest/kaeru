package app.kaeru.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.kaeru.di.IoDispatcher
import app.kaeru.di.PlaybackScope
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Cast, started off the main thread and behind the guard the rest of the app relies on.
 *
 * Starting the framework loads a Play services module and reads from disk, and it throws
 * outright on a phone that has no Play services or a version too old for Cast. Both of those
 * belong off the launch path, so [initialize] hands the work to the `Executor` overload Google
 * added for exactly this and returns immediately; [isAvailable] flips when the task comes back,
 * and stays false when it does not. Nothing before the first frame waits on any of it.
 *
 * Everything after the task completes runs on the main thread: the task's callbacks land there
 * by default, and so does every caller.
 */
@UnstableApi
@Singleton
class PlayServicesCastFramework @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:PlaybackScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CastFramework {

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /** Written by the task's callback on the main thread, read there too; volatile for honesty. */
    @Volatile
    private var castContext: CastContext? = null

    private var starting = false

    /** One at a time: the receiver is one device, and a second player would fight the first. */
    private var castEngine: CastPlaybackEngine? = null

    override fun initialize() {
        if (starting) return
        starting = true
        // Never fatal, at any stage: a phone that cannot cast keeps `isAvailable` false and
        // loses a button, which is the whole point of putting a seam here.
        runCatching {
            CastContext.getSharedInstance(context, io.asExecutor())
                .addOnSuccessListener { framework ->
                    castContext = framework
                    _isAvailable.value = true
                }
                .addOnFailureListener { castContext = null }
        }
    }

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

    override fun receiverName(): String? =
        runCatching { castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName }.getOrNull()

    override fun releaseEngine() {
        castEngine?.shutdown()
        castEngine = null
    }

    /** Never throws: a disconnect that fails leaves the session up, which the viewer can see. */
    override fun endSession() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }
}
