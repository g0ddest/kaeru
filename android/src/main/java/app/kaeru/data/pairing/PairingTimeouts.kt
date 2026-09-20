package app.kaeru.data.pairing

/**
 * Every clock the hand-off runs on, in one place so the two halves can be read against each other.
 *
 * They are injected rather than constant so a test can compress them: a slow-client test that has
 * to wait out the production deadline is a five-second test, and a five-second test gets deleted.
 */
data class PairingTimeouts(
    /** Phone → television. A device on the same Wi-Fi answers a SYN in milliseconds. */
    val connectMs: Int = 5_000,

    /**
     * The phone's whole request, from connect to the last byte of the answer. Long because the
     * wait is not the network: the television makes its own round trip to Shikimori while the
     * phone holds the connection open, and a short deadline here reports a failure for a sign-in
     * that in fact succeeded.
     */
    val requestMs: Int = 20_000,

    /**
     * Television: from accepting a connection to having the whole request. Short, because the
     * phone sends one small POST in one go, and because this server handles one connection at a
     * time — a caller allowed to dawdle here is a caller that has switched pairing off for
     * everybody else.
     */
    val acceptMs: Long = 5_000,

    /** Television: one read. A dead peer is dropped rather than waited on. */
    val readMs: Int = 2_000,
)
