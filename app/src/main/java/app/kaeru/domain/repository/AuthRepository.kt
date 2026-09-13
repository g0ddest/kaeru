package app.kaeru.domain.repository

import kotlinx.coroutines.flow.Flow

const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
const val MOBILE_REDIRECT = "kaeru://oauth"

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
