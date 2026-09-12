package app.kaeru.player

import app.kaeru.di.LocalEngine
import app.kaeru.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /**
     * Starts listening, once. Every screen that shows a cast button calls this on the way in,
     * so whichever of them the viewer reaches first arms it. Main thread only.
     */
    fun start() {
        if (listening?.isActive == true) return
        // No framework, no listening: a phone without Play services must not pay for a
        // subscription it can never be told anything through.
        if (!framework.isAvailable) return
        listening = scope.launch {
            framework.connections.collect { connection ->
                val engine = when (connection) {
                    CastConnection.CONNECTED -> framework.engine()
                    CastConnection.DISCONNECTED -> local
                } ?: return@collect
                // The position is read here rather than reported by the framework: the
                // controller's own state is the last one anything agreed on, and it survives a
                // receiver that has already stopped answering.
                controller.switchEngine(engine, controller.state.value.positionMs)
            }
        }
    }

    /** The viewer asked to stop casting. The disconnect comes back as [CastConnection.DISCONNECTED]. */
    fun disconnect() = framework.endSession()
}
