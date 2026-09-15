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

/** The device's own storage refused a write: a failed statement, a locked or corrupt database. */
class StorageFailure(cause: Throwable) : Exception(cause.message, cause)

/** An OAuth redirect callback did not match an authorization this app started. */
class AuthCallbackRejected(message: String) : Exception(message)

/** Why the source would not serve us. The two read very differently to a user, so they get separate copy. */
enum class SourceUnavailableReason {
    /** No token could be obtained, so we never got to ask. */
    NO_KEY,

    /** The source answered and turned us away: a non-2xx, or a failure we cannot classify. */
    REJECTED,

    /**
     * There is no network at all, and this episode is not on the device.
     *
     * Separate from [NetworkUnavailable] because the answer is different: a viewer in a tunnel
     * cannot fix their connection, but they can download the next episode before the next
     * tunnel, and that is what the copy for this one says.
     */
    OFFLINE,
}

/** The video source is reachable but will not serve us. */
class SourceUnavailable(val reason: SourceUnavailableReason, cause: Throwable? = null) :
    Exception("Video source unavailable: $reason", cause)

/** The source has nothing to play: no entry for this anime at all, or not this episode yet. */
class EpisodeNotAvailable(val animeId: Int, val episode: Int? = null) :
    Exception("No source for anime $animeId episode ${episode ?: "-"}")

/**
 * A Chromecast took the episode and never started playing it — no error from the receiver,
 * no first frame, nothing.
 *
 * Separate from [SourceUnavailable] on purpose: a receiver that will not take a stream and a
 * source that is down are the same silence from the phone's point of view, but only one of them
 * is fixed by trying again later, and telling a viewer their source is down when their
 * television is the problem sends them looking in the wrong place.
 */
class CastLoadFailed(cause: Throwable? = null) : Exception("Cast receiver did not load the stream", cause)

/**
 * The source's pages or responses no longer look the way the scrapers expect.
 * Only a new app build fixes it, so the copy says so instead of offering a retry.
 */
class SourceFormatChanged(val step: String, cause: Throwable? = null) :
    Exception("Video source format changed at $step", cause)

/** Why a hand-off between the phone and the television did not happen. */
enum class PairingFailureReason {
    /** The `kaeru://pair` link was malformed, or pointed somewhere outside the local network. */
    BAD_LINK,

    /** The television answered and turned the code away: a stale QR, or an exchange it could not finish. */
    REFUSED,

    /** Nothing answered at the address in the QR code. */
    UNREACHABLE,

    /** This television has no address on a local network, so there is nothing to put in a QR code. */
    NO_LOCAL_ADDRESS,

    /**
     * The offer was stopped or replaced while it was still being made.
     *
     * Never shown: whoever superseded it has already cancelled the work that would have reported
     * it, and what the viewer sees is whatever took its place.
     */
    SUPERSEDED,
}

/** A television could not be signed in from the phone that scanned its QR code. */
class PairingFailed(val reason: PairingFailureReason, cause: Throwable? = null) :
    Exception("Pairing failed: $reason", cause)

/**
 * A download was refused because it would not fit inside the storage limit the viewer set.
 *
 * Both numbers travel with it so the copy can say what the limit is and how much of it is gone,
 * which is the difference between «не хватает места» and an instruction the viewer can act on.
 */
class DownloadLimitReached(val limitBytes: Long, val usedBytes: Long) :
    Exception("Download limit reached: $usedBytes of $limitBytes bytes used")
