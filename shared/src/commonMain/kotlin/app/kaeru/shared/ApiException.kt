package app.kaeru.shared

/**
 * A request the server answered with something other than success, as a number rather than a
 * sentence.
 *
 * Everything this module throws crosses into Swift as an `NSError`, and for a long time the only
 * thing that survived the crossing was the message — so the app worked out whether a token had
 * expired by looking for «401» inside a string. That reading breaks the moment a message is
 * reworded or localised, and it breaks silently: the refresh simply stops happening and the viewer
 * is signed out at the next request.
 *
 * Public, and therefore exported, for exactly that reason: Swift reads [status] and [oauthError]
 * off the exception itself.
 *
 * Neither the request URL nor the response body is in [message]: both can carry credentials, and an
 * `NSError` ends up in crash reports.
 *
 * @param oauthError the `error` field of an OAuth failure — `invalid_grant` for a refresh token the
 *   server will not honour again, which is the one failure that means «sign in again» rather than
 *   «try again».
 */
class ApiException(
    val status: Int,
    val oauthError: String? = null,
    message: String = describe(status, oauthError),
) : Exception(message) {
    companion object {
        internal fun describe(status: Int, oauthError: String?): String = when {
            oauthError != null -> "OAuth failed (HTTP $status): $oauthError."
            status == 401 -> "Authentication failed (HTTP 401); refresh the session and retry once."
            else -> "Request failed (HTTP $status)."
        }
    }
}
