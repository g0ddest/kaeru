package app.kaeru.domain.pairing

/**
 * The television's half of the hand-off: a single endpoint, open only while the login screen is.
 *
 * The code that arrives is never stored and never forwarded anywhere except to the token request
 * the television makes itself, so the only thing that crosses the local network is a one-time
 * authorization code that is spent the moment it lands.
 */
interface TvPairingServer {

    /** Where the phone should send the code — the address that goes into the QR. */
    data class Endpoint(val host: String, val port: Int)

    /**
     * Starts listening for [session]'s nonce and hands every accepted code to [onCode], which is
     * the token exchange. Fails when this device has no address on a local network to advertise,
     * or when no port could be opened; the login screen then falls back to the typed code.
     *
     * One successful [onCode] is all a session is worth: everything after it is refused.
     *
     * Suspends because opening the port means reading this device's own interfaces and binding a
     * socket, neither of which belongs on the thread that is drawing the screen asking for it.
     */
    suspend fun start(
        session: PairingSession,
        onCode: suspend (code: String, redirectUri: String) -> Result<Unit>,
    ): Result<Endpoint>

    /** Closes the port. Safe to call when nothing was started, and safe to call twice. */
    fun stop()
}
