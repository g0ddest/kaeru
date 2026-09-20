package app.kaeru.player

import app.kaeru.di.LocalEngine
import app.kaeru.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that connects Google Cast to playback.
 *
 * A receiver appears and the episode goes to it; the receiver goes away and the episode comes
 * back to the phone, at the second it reached. Nothing else here knows about casting: the
 * controller is handed another [PlaybackEngine] and carries on with the same episode, the same
 * track and the same progress.
 *
 * It lives as long as the process, because a session can end while no player screen is up —
 * and the phone would otherwise be left believing it is still casting.
 */
@Singleton
class CastSessionBridge @Inject constructor(
    private val framework: CastFramework,
    private val controller: PlaybackController,
    @param:LocalEngine private val local: PlaybackEngine,
    @param:PlaybackScope private val scope: CoroutineScope,
) {

    private var listening: Job? = null

    private val _receiverName = MutableStateFlow<String?>(null)

    /** What the receiver calls itself while it has the picture, for a screen to say so. */
    val receiverName: StateFlow<String?> = _receiverName.asStateFlow()

    /**
     * Starts the framework and listens, once. Every screen that shows a cast button calls this
     * on the way in, so whichever of them the viewer reaches first arms it. Main thread only,
     * and it does no work there: starting the framework is a background task.
     */
    fun start() {
        if (listening?.isActive == true) return
        framework.initialize()
        listening = scope.launch {
            // Waited for rather than asked about: the framework takes a Play services round
            // trip to come up, so the answer at the first screen is "not yet", not "no". On a
            // phone that cannot cast this simply parks here for the life of the process.
            framework.isAvailable.first { it }
            framework.connections.collect { connection ->
                val engine = when (connection) {
                    CastConnection.CONNECTED -> framework.engine()?.also {
                        _receiverName.value = framework.receiverName()
                    }
                    CastConnection.DISCONNECTED -> local
                } ?: return@collect
                // The position is read here rather than reported by the framework: the
                // controller's own state is the last one anything agreed on, and it survives a
                // receiver that has already stopped answering.
                controller.switchEngine(engine, controller.state.value.positionMs)
                if (connection == CastConnection.DISCONNECTED) {
                    _receiverName.value = null
                    // Only now, with the controller back on the local engine and nothing left
                    // pointing at it: the receiver's player is no use until the next session,
                    // and holding it holds a connection to a device that has gone.
                    framework.releaseEngine()
                }
            }
        }
    }

    /**
     * The viewer asked to stop casting. Only the session is ended; the switch back is the
     * framework's announcement to make, so this button and the system output switcher take
     * exactly the same path.
     */
    fun disconnect() = framework.endSession()
}
