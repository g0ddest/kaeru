package app.kaeru.domain.together

import kotlinx.coroutines.flow.Flow

/** A phone listening for its friend on the local network: the address a router gave it, and a port. */
data class LanEndpoint(val host: String, val port: Int)

/**
 * What the screen has to say about the channel. [RECONNECTING] is the honest one: the session is
 * not over, but nothing is getting through, and a viewer deserves to be told that rather than
 * shown a picture that has silently stopped following their friend.
 */
enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

/**
 * A way for two phones to say things to each other, whichever way they can reach.
 *
 * There are two, and the session above does not know which it has: direct sockets when both phones
 * are on one Wi-Fi, and a relay when they are not. Both deliver in order, both carry frames from
 * [TogetherCodec], and neither of them ever sees a plaintext message.
 */
interface WatchTogetherTransport {

    val state: Flow<ConnectionState>

    /**
     * Opens the channel and yields what arrives on it, decoded, until it closes for good.
     *
     * Failures are values here, not throws. A frame that will not decrypt, or one larger than the
     * protocol allows, is one frame's problem — the collector is told and the channel carries on,
     * because a corrupted packet is not a reason to end somebody's film. The flow ends only when
     * the channel is finished: the peer left, or reconnection ran out of time.
     */
    fun connect(link: RoomLink, asHost: Boolean): Flow<Result<TogetherMessage>>

    /**
     * Throws [app.kaeru.domain.error.TogetherFailed] when there is nothing to write to. Callers
     * treat a failed send as a dropped action, not as a session that has ended — the state flow
     * is what says whether the channel is still there.
     */
    suspend fun send(message: TogetherMessage)

    suspend fun close()

    /**
     * Where a friend on the same Wi-Fi should knock, or `null` when this transport does not take
     * incoming connections. Reading it is what opens the port, so the host can put the address in
     * a link before anybody is listening for messages on it; [close] gives the port back.
     */
    fun hostEndpoint(): LanEndpoint?
}

/**
 * Which way to reach the room in this link.
 *
 * A link that names a local address is one a friend got while sitting in the same room, and direct
 * sockets are both faster and nobody else's business. Everything else goes through the relay.
 */
fun interface TransportFactory {
    fun forLink(link: RoomLink): WatchTogetherTransport
}
