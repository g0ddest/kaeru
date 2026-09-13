package app.kaeru.domain.pairing

/** The phone's half of the hand-off: one POST to the television named by [PairingRequest]. */
interface PairingClient {
    /**
     * Sends a fresh authorization [code] to the television, together with the [redirectUri] it was
     * obtained with — Shikimori checks that a token request repeats the redirect of the
     * authorization it belongs to, and that authorization happened on this phone.
     */
    suspend fun send(request: PairingRequest, code: String, redirectUri: String): Result<Unit>
}
