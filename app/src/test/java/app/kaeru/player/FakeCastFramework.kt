package app.kaeru.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * A Cast framework a test can switch on and off.
 *
 * The interesting case is [isAvailable] = false: a phone with no Google Play services, or one
 * whose Play services are too old, where [CastSessionBridge] has to stay out of the way rather
 * than throw. [subscriptions] proves it never even listened.
 */
class FakeCastFramework(
    override val isAvailable: Boolean = true,
    private val castEngine: PlaybackEngine? = FakePlaybackEngine(),
) : CastFramework {

    private val emitted = MutableSharedFlow<CastConnection>(extraBufferCapacity = 8)

    /** How many collectors ever started. Two would mean the bridge subscribed twice. */
    var subscriptions = 0
        private set

    /** How many times something asked to be disconnected from the receiver. */
    var endedSessions = 0
        private set

    override val connections: Flow<CastConnection> = emitted.onSubscription { subscriptions += 1 }

    override fun engine(): PlaybackEngine? = castEngine

    override fun endSession() {
        endedSessions += 1
    }

    fun connect() = emitted.tryEmit(CastConnection.CONNECTED)

    fun disconnect() = emitted.tryEmit(CastConnection.DISCONNECTED)
}
