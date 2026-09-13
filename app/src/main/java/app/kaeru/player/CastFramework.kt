package app.kaeru.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A receiver took the picture, or gave it back. */
enum class CastConnection { CONNECTED, DISCONNECTED }

/**
 * Everything the app needs from Google Cast, behind one seam.
 *
 * It exists because the Cast framework is neither always there nor ever quick. Starting it
 * loads a Play services module and touches the disk, and on a phone without Play services it
 * fails outright — so it is started off the main thread, once, and everything here answers
 * "not yet" until it is up and "no" forever when it cannot be.
 *
 * [isAvailable] is therefore a flow rather than a question: false at every cold start, true
 * some hundreds of milliseconds later on a phone that can cast, and false for good on one that
 * cannot. Nothing may treat the first false as an answer.
 *
 * Everything here is called from the main thread, which is where the Cast framework insists on
 * being used and where the playback scope already runs.
 */
interface CastFramework {
    /**
     * Starts the framework if it is not started already and returns at once, having done no
     * work on the calling thread. Idempotent: every screen that can cast calls it.
     */
    fun initialize()

    /** Whether the framework is up. Starts false, and stays false on a device without Cast. */
    val isAvailable: StateFlow<Boolean>

    /**
     * Receivers connecting and disconnecting, in order. A receiver already connected when a
     * collector arrives is announced to it, so a screen that opens mid-session still knows.
     */
    val connections: Flow<CastConnection>

    /**
     * The engine that plays on the connected receiver, or null when there is no framework to
     * play through. One at a time: the receiver is one device, not one per episode.
     */
    fun engine(): PlaybackEngine?

    /** What the connected receiver calls itself, for a screen to say where the picture went. */
    fun receiverName(): String?

    /** Gives the receiver's player back once the session is over. The next one builds another. */
    fun releaseEngine()

    /** Disconnects from the receiver, which is what brings playback back to the phone. */
    fun endSession()
}
