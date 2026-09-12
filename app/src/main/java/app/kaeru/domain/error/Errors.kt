package app.kaeru.domain.error

/**
 * Failures the UI has to explain to a user. The data layer translates transport exceptions into
 * these on the way out, so `ui.*` never has to know which HTTP client produced a failure.
 */

/** Shikimori could not be reached at all: no network, DNS failure, timeout, dropped connection. */
class NetworkUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** Shikimori answered with a non-2xx status. */
class HttpError(val code: Int, cause: Throwable? = null) : Exception("HTTP $code", cause)

/** The account changed, or was never prepared, while an account-scoped operation was running. */
class AccountSessionChanged(message: String) : IllegalStateException(message)

/** An OAuth redirect callback did not match an authorization this app started. */
class AuthCallbackRejected(message: String) : Exception(message)
