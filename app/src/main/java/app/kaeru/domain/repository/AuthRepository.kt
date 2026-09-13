package app.kaeru.domain.repository

import kotlinx.coroutines.flow.Flow

const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
const val MOBILE_REDIRECT = "kaeru://oauth"

/**
 * An authorization this app started on behalf of something else, and will not exchange itself.
 *
 * The `state` comes back to the caller instead of being remembered by the repository, because the
 * code it produces is going to another device: whoever asked for this is the only thing that can
 * meaningfully check the callback, and the repository must be left with nothing pending.
 */
data class PairingAuthorization(val url: String, val state: String)

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>

    /** Starts an authorization attempt and arms the `state` its callback has to echo back. */
    fun authorizeUrl(redirectUri: String): String

    /**
     * Handles a browser redirect back to [MOBILE_REDIRECT]. Any app or web page can fire that
     * deep link, so the callback is honoured only when [state] matches the attempt this app
     * started and no account is signed in yet.
     */
    suspend fun exchangeRedirectCode(code: String, state: String?): Result<Unit>

    /**
     * Handles an out-of-band code the user read off the Shikimori page and typed in (TV). The act
     * of typing it is the user's confirmation, so this path carries no `state`: nothing but the
     * person at the device can deliver a code here.
     */
    suspend fun exchangeTypedCode(code: String): Result<Unit>

    /**
     * Builds an authorization page for a code that will be handed to a television, **without**
     * arming this device's own callback.
     *
     * [authorizeUrl] remembers the `state` it issued, and only [exchangeRedirectCode] forgets it
     * again. A pairing never calls that, so using [authorizeUrl] here would leave a live `state`
     * behind for good — and `kaeru://oauth` is a custom scheme any app can register, so anything
     * that watched one redirect could later replay that `state` and sign this phone into an
     * account of its choosing. The caller keeps the `state` and checks the callback itself.
     */
    fun pairingAuthorization(): PairingAuthorization

    /**
     * Handles an authorization code a phone obtained through its own browser session and handed to
     * this device over the local network.
     *
     * It carries no `state`, because the authorization did not start here: what stands in for it is
     * the one-time nonce the phone had to read off this screen. [redirectUri] is the phone's, not
     * this device's — Shikimori checks that a token request repeats the redirect of the
     * authorization it belongs to — and is refused unless it is one this app owns.
     */
    suspend fun exchangePairedCode(code: String, redirectUri: String): Result<Unit>

    suspend fun logout()
}
