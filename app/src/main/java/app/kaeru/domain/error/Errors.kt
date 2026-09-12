package app.kaeru.domain.error

/**
 * Failures the UI has to explain to a user. The data layer translates transport exceptions into
 * these on the way out, so `ui.*` never has to know which HTTP client produced a failure.
 */

/** A remote service could not be reached at all: no network, DNS failure, timeout, dropped connection. */
class NetworkUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** Shikimori answered with a non-2xx status. */
class HttpError(val code: Int, cause: Throwable? = null) : Exception("HTTP $code", cause)

/** The account changed, or was never prepared, while an account-scoped operation was running. */
class AccountSessionChanged(message: String) : IllegalStateException(message)

/** An OAuth redirect callback did not match an authorization this app started. */
class AuthCallbackRejected(message: String) : Exception(message)

/** Why the source would not serve us. The two read very differently to a user, so they get separate copy. */
enum class SourceUnavailableReason {
    /** No token could be obtained, so we never got to ask. */
    NO_KEY,

    /** The source answered and turned us away: a non-2xx, or a failure we cannot classify. */
    REJECTED,
}

/** The video source is reachable but will not serve us. */
class SourceUnavailable(val reason: SourceUnavailableReason, cause: Throwable? = null) :
    Exception("Video source unavailable: $reason", cause)

/** The source has nothing to play: no entry for this anime at all, or not this episode yet. */
class EpisodeNotAvailable(val animeId: Int, val episode: Int? = null) :
    Exception("No source for anime $animeId episode ${episode ?: "-"}")

/**
 * The source's pages or responses no longer look the way the scrapers expect.
 * Only a new app build fixes it, so the copy says so instead of offering a retry.
 */
class SourceFormatChanged(val step: String, cause: Throwable? = null) :
    Exception("Video source format changed at $step", cause)
