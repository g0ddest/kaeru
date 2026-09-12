package app.kaeru.player

import kotlinx.coroutines.flow.Flow

/** A receiver took the picture, or gave it back. */
enum class CastConnection { CONNECTED, DISCONNECTED }

/**
 * Everything the app needs from Google Cast, behind one seam.
 *
 * It exists because the Cast framework is not always there: a phone without Google Play
 * services, or with a version too old for it, throws the moment `CastContext` is asked for.
 * That has to cost the cast button and nothing else, so the whole framework is reduced to
 * three questions — can we cast, is a receiver connected, and what plays on it — and the one
 * implementation that touches Play services answers them behind a guard.
 *
 * Everything here is called from the main thread, which is where the Cast framework insists
 * on being used and where the playback scope already runs.
 */
interface CastFramework {
    /** False when Google Play services are missing, out of date, or refused to start the framework. */
    val isAvailable: Boolean

    /**
     * Receivers connecting and disconnecting, in order. A receiver already connected when a
     * collector arrives is announced to it, so a screen that opens mid-session still knows.
     * Empty when [isAvailable] is false.
     */
    val connections: Flow<CastConnection>

    /**
     * The engine that plays on the connected receiver, or null when there is no framework to
     * play through. One per process: the receiver is one device, not one per episode.
     */
    fun engine(): PlaybackEngine?

    /** Disconnects from the receiver, which is what brings playback back to the phone. */
    fun endSession()
}
