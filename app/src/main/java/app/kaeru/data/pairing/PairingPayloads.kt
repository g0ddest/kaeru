package app.kaeru.data.pairing

import kotlinx.serialization.Serializable

/**
 * The one message the phone sends the television, and the two it can get back.
 *
 * No field carries a default: the shared `Json` is configured with `encodeDefaults = false`, so a
 * defaulted `ok` would be left out of the answer entirely, and a defaulted `nonce` would let a
 * request that omits it parse as an empty one instead of being refused.
 */
@Serializable
internal data class PairingPayload(val nonce: String, val code: String, val redirectUri: String)

@Serializable
internal data class PairingAccepted(val ok: Boolean)

@Serializable
internal data class PairingRefused(val error: String)

/** The reasons a television gives, chosen to be readable in a log rather than shown to anybody. */
internal object PairingErrors {
    const val BAD_REQUEST = "bad_request"
    const val NONCE_MISMATCH = "nonce_mismatch"
    const val EXPIRED = "expired"
    const val ALREADY_PAIRED = "already_paired"
    const val EXCHANGE_FAILED = "exchange_failed"
}
