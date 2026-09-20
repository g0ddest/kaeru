package app.kaeru.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * A Cast framework a test can switch on, switch on late, or never switch on at all.
 *
 * The interesting cases are the ones a phone actually has: one with no Google Play services,
 * where [CastSessionBridge] has to stay out of the way rather than throw, and one where the
 * framework is still starting when the app opens. [subscriptions] proves the bridge listened
 * once, or never.
 */
class FakeCastFramework(
    available: Boolean = true,
    private val castEngine: PlaybackEngine? = FakePlaybackEngine(),
    private val receiver: String? = "Телевизор в гостиной",
) : CastFramework {

    private val emitted = MutableSharedFlow<CastConnection>(extraBufferCapacity = 8)
    private val available = MutableStateFlow(available)

    override val isAvailable: StateFlow<Boolean> = this.available.asStateFlow()

    /** How many collectors ever started. Two would mean the bridge subscribed twice. */
    var subscriptions = 0
        private set

    /** How many times something asked the framework to start. */
    var initializations = 0
        private set

    /** How many times something asked to be disconnected from the receiver. */
    var endedSessions = 0
        private set

    /** How many times the receiver's player was given back. */
    var releasedEngines = 0
        private set

    override val connections: Flow<CastConnection> = emitted.onSubscription { subscriptions += 1 }

    override fun initialize() {
        initializations += 1
    }

    override fun engine(): PlaybackEngine? = castEngine

    override fun receiverName(): String? = receiver

    override fun releaseEngine() {
        releasedEngines += 1
    }

    override fun endSession() {
        endedSessions += 1
    }

    /** The framework finished starting, the way the Play services task's callback does. */
    fun becomeAvailable() {
        available.value = true
    }

    fun connect() = emitted.tryEmit(CastConnection.CONNECTED)

    fun disconnect() = emitted.tryEmit(CastConnection.DISCONNECTED)
}
